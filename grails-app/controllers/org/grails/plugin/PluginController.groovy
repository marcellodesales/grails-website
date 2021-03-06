package org.grails.plugin

import grails.converters.JSON
import grails.plugin.springcache.annotations.*

import javax.persistence.OptimisticLockException
import javax.servlet.http.HttpServletResponse

import org.apache.shiro.SecurityUtils
import org.codehaus.groovy.grails.commons.ConfigurationHolder
import org.codehaus.groovy.grails.web.metaclass.RedirectDynamicMethod
import org.codehaus.groovy.grails.web.servlet.HttpHeaders
import org.compass.core.engine.SearchEngineQueryParseException
import org.grails.auth.Role
import org.grails.comments.*
import org.grails.taggable.*
import org.grails.tags.TagNotFoundException
import org.grails.wiki.BaseWikiController
import org.springframework.web.context.request.RequestContextHolder

import net.sf.ehcache.Element

class PluginController extends BaseWikiController {

    static String HOME_WIKI = 'PluginHome'
    static int PORTAL_MAX_RESULTS = 5
    static int PORTAL_MIN_RATINGS = 1
    
    static allowedMethods = [update: "PUT"]
    
    def taggableService
    def wikiPageService
    def pluginService
    def commentService
    def grailsUrlMappingsHolder

    def index = {
        redirect(controller:'plugin', action:'home', params:params)
    }

    def home = {
        def queryParams = [:]
        queryParams.offset = params.offset ?: 0
        queryParams.sort = params.sort ?: 'name'
        queryParams.order = params.order ?: 'asc'

        // We only want to display 5 plugins at a time in the web interface,
        // but JSON and XML data shouldn't be limited in that way.
        if (request.format == 'html') {
            queryParams.max = PORTAL_MAX_RESULTS
            params.max = PORTAL_MAX_RESULTS
        }

        // If no category is specified, default to 'featured' for the
        // web interface, and 'all' for JSON and XML requests.
        def category = params.remove('category') ?: (request.format == 'html' ? 'featured' : 'all')
        
        log.debug "plugin home: $params"
        
        def currentPlugins = []
        def totalPlugins = 0

        try {
            if (params.q) {
                // Build the arguments for the search, starting with the query
                // string. We add the category if it's defined. Finally we add
                // the search options.
                def args = [params.q]
                if (category) args << category
                args << queryParams

                (currentPlugins, totalPlugins) = pluginService.searchWithTotal(*args)
            }
            else {
                (currentPlugins, totalPlugins) = pluginService."list${category.capitalize()}PluginsWithTotal"(queryParams)
            }
        }
        catch (MissingMethodException ex) {
            log.error "Unable to list plugins for category '${category}': ${ex.message}"
            response.sendError 404
            return
        }

        withFormat {
            html {
                [currentPlugins:currentPlugins, category:category,totalPlugins:totalPlugins]
            }
            json {
                render(contentType:"text/json") {
                    plugins = currentPlugins?.collect { Plugin p ->
                        return {
                            name = p.name
                            version = p.currentRelease
                            title = p.title
                            author = p.author
                            authorEmail = p.authorEmail
                            description = p.summary
                            grailsVersion = p.grailsVersion
                            documentation = p.documentationUrl
                            file = p.downloadUrl
                            rating = p.avgRating
                            
                            if (p.issuesUrl) issues = p.issuesUrl
                            if (p.scmUrl) scm = p.scmUrl
                        }
                    } ?: []
                }
            }
            xml {
                render(contentType:"application/xml") {
                    plugins {
                         for (Plugin p in currentPlugins) {
                            plugin(name: p.name, 'latest-release': p.currentRelease) {
                                release(version: p.currentRelease) {
                                    title p.title
                                    author p.author
                                    authorEmail p.authorEmail
                                    description p.summary
                                    grailsVersion p.grailsVersion
                                    documentation p.documentationUrl
                                    file p.downloadUrl
                                    rating p.avgRating
                                    
                                    if (p.issuesUrl) issues p.issuesUrl
                                    if (p.scmUrl) scm p.scmUrl
                                }
                            }
                         }
                    }
                }
            }
        }
    }

    def browseByName = {
        params.sort = "name"
        params.order = "asc"

        def (currentPlugins, totalPlugins) = pluginService.listAllPluginsWithTotal(params)
        currentPlugins = currentPlugins.groupBy { it.name ? it.name[0].toUpperCase() : 'A' }

        return [currentPlugins: currentPlugins, totalPlugins: totalPlugins]
    }

    def forum = {}

    def all = {
        render view:"home", model:[
                originAction:"all",
                pluginList:Plugin.list(max:10, offset: params.offset?.toInteger(), cache:true, sort:"name") ]
    }

    def list = {
        permRedirect "browseByName", params
    }
    

    def show = {
        def plugin = byName(params)
        if (!plugin) {
            return redirect(action:'createPlugin', params:params)
        }

        def userRating
        if (request.user) {
            userRating = plugin.userRating(request.user)
        }

        // TODO: figure out why plugin.ratings.size() is always 1
        render view:'showPlugin', model:[plugin:plugin, userRating: userRating]
    }

    /**
     * Plugin 'ping'. Should only be accessible from a PUT. It extracts
     * the location of the plugin's deployment repository from the request
     * and queues up a job to update the plugin's details in the database
     * from the POM and plugin descriptor stored in the repository.
     */
    def update = {
        // Start by getting the named plugin if it exists.
        def plugin = Plugin.findByName(params.name)

        // Check the payload. There should be a 'url' parameter containing
        // the location of the repository to which the plugin was deployed.
        // If the parameter doesn't exist or it's not a URL, we return a 400.
        def data = JSON.parse(request)
        if (!data.url) {
            render contentType: "application/json", status: 400, {
                message = "No repository URI provided"
            }
            return
        }

        try {
            def uri = new URI(data.url)

            if (!uri.absolute) {
                    render contentType: "application/json", status: 400, {
                        message = "Relative repository URI not supported: ${uri}"
                    }
                return
            }

            // Default to it not being a snapshot release if 'isSnapshot' is not provided.
            publishEvent(new PluginUpdateEvent(this, data.name, data.version, data.group, data.isSnapshot ?: false, uri))

            render contentType: "application/json", {
                message = "OK"
            }
        }
        catch (URISyntaxException ex) {
            render contentType: "application/json", status: 400, {
                message = "Invalid repository URI: ${data.url}"
            }
            return
        }
        catch (Exception ex) {
            log.error "Plugin update failed", ex
            render contentType: "application/json", status: 500, {
                message = "Internal server error: ${ex.message}"
            }
            return
        }
    }

    def editPlugin = {
        def plugin = Plugin.get(params.id)
        if(plugin) {
            if(request.method == 'POST') {
                // Update the plugin's properties, but exclude 'zombie'
                // because only an administrator can set that.
                bindData plugin, params, [ "zombie" ]
                if (!plugin.validate()) {
                    return render(view:'editPlugin', model: [plugin:plugin])
                }
                if (!plugin.isNewerThan(params.currentRelease)) {
                    plugin.lastReleased = new Date();
                }
                // Update 'zombie' if we have an administrator.
                if (SecurityUtils.subject.hasRole(Role.ADMINISTRATOR)) {
                    plugin.zombie = params.zombie ?: false
                }
                
                pluginService.savePlugin(plugin)
                redirect(action:'show', params:[name:plugin.name])
            } else {
                return render(view:'editPlugin', model: [plugin:plugin])
            }
        } else {
            response.sendError 404
        }
    }

    def createPlugin = {
        // just in case this was an ad hoc creation where the user logged in during the creation...
        if (params.name) params.name = params.name - '?action=login'
        def plugin = new Plugin(params)
        if(request.method == 'POST') {
            pluginService.initNewPlugin(plugin, request.user)
            
            if (pluginService.savePlugin(plugin)) {
                redirect(action:'show', params: [name:plugin.name])
            } else {
                render(view:'createPlugin', model:[plugin:plugin])
            }
        } else {
            render(view:'createPlugin', model:[plugin:plugin])
        }
    }

    def deletePlugin = {
        def plugin = byName(params)
        log.warn "Deleting Plugin: $plugin"
        plugin.delete()
        redirect(view:'index')
    }

    def search = {
        if(params.q) {
            try {
                def searchResult = Plugin.search(params.q, offset: params.offset)
                searchResult.results = searchResult.results.findAll{it}.unique { it.title }
                flash.message = "Found $searchResult.total results!"
                flash.next()
                render view: "searchResults", model: [searchResult: searchResult]
            }
            catch (SearchEngineQueryParseException ex) {
                render view: "searchResults", model: [parseException: true]
            }
            catch (org.apache.lucene.search.BooleanQuery.TooManyClauses ex) {
                render view: "searchResults", model: [clauseException: true]
            }
        }
        else {
            redirect(action:'home')
        }
   }

    def latest = {

        def engine = createWikiEngine()

         def feedOutput = {

            def top5 = Plugin.listOrderByLastUpdated(order:'desc', max:5, cache:true)
            title = "Grails New Plugins Feed"
            link = "http://grails.org/Plugins"
            description = "New and recently updated Grails Plugins"

            for(item in top5) {
                entry(item.title) {
                    link = "http://grails.org/plugin/${item.name.encodeAsURL()}"
                    author = item.author
                    publishedDate = item.lastUpdated
                    engine.render(item.description.body, context)
                }
            }
         }

        withFormat {
            html {
                redirect(action: "home")
            }
            rss {
                render(feedType:"rss",feedOutput)
            }
            atom {
                render(feedType:"atom", feedOutput)
            }
        }

    }

    def saveTab = {
        if (!params.id) {
            render template: "/shared/remoteError", model: [code: "page.id.missing"]
        }
        else {
            try {
                PluginTab pluginTab = wikiPageService.createOrUpdatePluginTab(
                        params.id.decodeURL(),
                        params.body,
                        request.user,
                        params.long('version'))

                if (pluginTab.hasErrors()) {
                    render(template: "/content/wikiEdit", model: [
                            wikiPage: pluginTab,
                            update: params.update,
                            editFormName: params.editFormName,
                            saveUri: g.createLink(action: "saveTab", id: pluginTab.title)])
                }
                else {
                    render(template: "/content/wikiShow", model: [
                            content: pluginTab,
                            message: "wiki.page.updated",
                            update: params.update,
                            latest: pluginTab.latestVersion])
                }
            }
            catch (OptimisticLockException ex) {
                def pluginTab = new PluginTab(title: params.id.decodeURL(), body: params.body)
                render(template: "/content/wikiEdit", model: [
                        wikiPage: pluginTab,
                        update: params.update,
                        editFormName: params.editFormName,
                        saveUri: g.createLink(action: "saveTab", id: pluginTab.title),
                        error: "page.optimistic.locking.failure"])
            }
        }
    }

    def postComment = {
        def plugin = Plugin.get(params.id)
        plugin.addComment(request.user, params.comment)
        plugin.save(flush:true)
        return render(template:'/comments/comment', var:'comment', bean:plugin.comments[-1])
    }

    def addTag = {
        def plugin = Plugin.get(params.id)
        params.newTag.trim().split(',').each { newTag ->
            plugin.addTag(newTag.trim())
        }
        Plugin.reindex(plugin)
        render(template:'tags', var:'plugin', bean:plugin)
    }

    def removeTag = {
        def plugin = Plugin.get(params.id)
        plugin.removeTag(params.tagName)
        plugin.save()
        Plugin.reindex(plugin)
        render(template:'tags', var:'plugin', bean:plugin)
    }

    def showTag = {
        permRedirect "browseByTag", params
    }

    def browseByTag = {
        try {
            def maxResults = params.int('max') ?: 10
            def offset = params.int('offset') ?: 0
            def (plugins, pluginCount) = pluginService.listPluginsByTagWithTotal(params.tagName, max: maxResults, offset: offset)
            return [currentPlugins: plugins, totalPlugins: pluginCount, tagName:params.tagName, max: maxResults, offset: offset]
        }
        catch (TagNotFoundException ex) {
            render view: "tagNotFound", model: [tagName: ex.tagName ?: '', msgCode: ex.code]
        }
    }

    /**
     * Displays a cloud of all the tags attached to plugins.
     */
    def browseTags = {
        // Get hold of all the plugin tags. This service method returns a map of tag
        // names to counts, i.e. how many plugins have been tagged with each tag.
        def allPluginTags = taggableService.getTagCounts("plugin").sort()
        [tags: allPluginTags]
    }

    def showComment = {
        def link = CommentLink.findByCommentAndType(Comment.get(params.id), 'plugin', [cache:true])
        def plugin = Plugin.get(link.commentRef)
        redirect(action:'show', params:[name:plugin.name], fragment:"comment_${params.id}")
    }

    private def byTitle(params) {
        Plugin.findByTitle(params.title.replaceAll('\\+', ' '), [cache:true])
    }

    private def byName(params) {
        Plugin.createCriteria().get {
            eq 'name', params.name
            join 'description'
            join 'installation'            
            join 'faq'                        
            join 'screenshots'        
            maxResults 1    
            cache true
        }
    }

    private void permRedirect(String action, urlParams) {
        def urlMapping = grailsUrlMappingsHolder.getReverseMapping("plugin", action, urlParams)
        response.setHeader HttpHeaders.LOCATION, urlMapping.createURL("plugin", action, urlParams, request.characterEncoding, null)
        response.status = HttpServletResponse.SC_MOVED_PERMANENTLY
        request[RedirectDynamicMethod.GRAILS_REDIRECT_ISSUED] = true
        RequestContextHolder.currentRequestAttributes().renderView = false
    }
}
