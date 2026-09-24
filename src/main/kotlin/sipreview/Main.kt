package sipreview

import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import sipreview.domain.*
import sipreview.persist.*
import java.io.File

fun main(args: Array<String>) {
    var host = "127.0.0.1"
    var port = 5380
    var dbPath = File(System.getProperty("user.dir"), "sip-review.db").absolutePath
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--host" -> { host = args[++i] }
            "--port" -> { port = args[++i].toInt() }
            "--db" -> { dbPath = args[++i] }
        }
        i++
    }
    embeddedServer(Netty, host = host, port = port) {
        appModule(dbPath)
    }.start(wait = true)
}

@Serializable
data class CreateProjectReq(val name: String, val seed: Long = 42L, val note: String = "")

@Serializable
data class ImportReq(val jsonl: String, val revisionOf: String? = null)

@Serializable
data class ParamsReq(
    val note: String,
    val timerT1Ms: Long? = null, val timerT2Ms: Long? = null, val timerT4Ms: Long? = null,
    val nonInviteTimeoutMs: Long? = null, val inviteCompletedMs: Long? = null,
    val retransmitWindowMs: Long? = null,
)

fun Application.appModule(dbPath: String) {
    val db = Database.open(dbPath)
    val clock = VirtualClock(0L)
    val service = ReviewService(db, clock)
    install(ContentNegotiation) { json(db.json) }
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (cause.message ?: cause::class.simpleName!!)))
        }
    }
    routing {
        get("/api/health") { call.respond(mapOf("ok" to true)) }
        get("/api/projects") { call.respond(mapOf("projects" to service.run { db.listProjects() })) }
        post("/api/projects") {
            val req = call.receive<CreateProjectReq>()
            call.respond(service.createProject(req.name, req.seed, req.note))
        }
        get("/api/projects/{id}") {
            val p = db.getProject(call.parameters["id"]!!) ?: return@get call.respondText("not found", status = HttpStatusCode.NotFound)
            call.respond(mapOf(
                "project" to p,
                "records" to db.allRecords(p.id),
                "activeRecords" to db.activeRecords(p.id),
                "paramRevisions" to db.listParamRevisions(p.id),
                "decisions" to db.listDecisions(p.id),
                "currentRun" to service.currentRun(p.id),
                "runs" to db.listRuns(p.id),
            ))
        }
        post("/api/projects/{id}/import") {
            val id = call.parameters["id"]!!
            val req = call.receive<ImportReq>()
            call.respond(service.importJsonl(id, req.jsonl, req.revisionOf))
        }
        post("/api/projects/{id}/params") {
            val id = call.parameters["id"]!!
            val req = call.receive<ParamsReq>()
            val cur = db.latestParamRevision(id)!!.params
            val next = cur.copy(
                timerT1Ms = req.timerT1Ms ?: cur.timerT1Ms,
                timerT2Ms = req.timerT2Ms ?: cur.timerT2Ms,
                timerT4Ms = req.timerT4Ms ?: cur.timerT4Ms,
                nonInviteTimeoutMs = req.nonInviteTimeoutMs ?: cur.nonInviteTimeoutMs,
                inviteCompletedMs = req.inviteCompletedMs ?: cur.inviteCompletedMs,
                retransmitWindowMs = req.retransmitWindowMs ?: cur.retransmitWindowMs,
            )
            call.respond(service.reviseParams(id, next, req.note))
        }
        post("/api/projects/{id}/decide") {
            val id = call.parameters["id"]!!
            call.respond(service.decide(id, call.receive()))
        }
        get("/api/projects/{id}/replay") {
            val id = call.parameters["id"]!!
            val at = call.request.queryParameters["atDecision"]
            call.respond(service.replayAt(id, at) ?: mapOf("error" to "无法重放"))
        }
        get("/api/projects/{id}/run") {
            val id = call.parameters["id"]!!
            val seq = call.request.queryParameters["paramSeq"]?.toLongOrNull()
            call.respond(service.currentRun(id, seq) ?: mapOf("error" to "无运行"))
        }
        get("/api/export") {
            call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=sip-review-export.json")
            call.respondText(service.exportJson(), ContentType.Application.Json)
        }
        post("/api/import") {
            val text = call.receiveText()
            call.respond(service.importArchiveJson(text))
        }
        get("/api/case/{name}") {
            val name = call.parameters["name"]!!.replace("..", "")
            val file = File("cases/$name")
            if (!file.exists()) return@get call.respondText("case not found", status = HttpStatusCode.NotFound)
            call.respondText(file.readText(), ContentType.Text.Plain)
        }
        get("/api/cases") {
            call.respond(mapOf("cases" to File("cases").listFiles()?.map { it.name }?.sorted() ?: emptyList<String>()))
        }
        get("/") { call.respondRedirect("/web/index.html") }
        static("/web") { resources("web") }
    }
}
