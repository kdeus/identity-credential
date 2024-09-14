package com.android.identity.wallet.server

import com.android.identity.cbor.Cbor
import com.android.identity.crypto.Algorithm
import com.android.identity.crypto.Crypto
import com.android.identity.flow.handler.AesGcmCipher
import com.android.identity.flow.handler.FlowNotifications
import com.android.identity.flow.handler.SimpleCipher
import com.android.identity.flow.server.FlowEnvironment
import com.android.identity.flow.server.Resources
import com.android.identity.flow.server.Storage
import com.android.identity.issuance.hardcoded.IssuerDocument
import com.android.identity.issuance.hardcoded.IssuingAuthorityState
import com.android.identity.server.BaseHttpServlet
import com.android.identity.util.Logger
import io.ktor.utils.io.core.toByteArray
import jakarta.servlet.http.Cookie
import kotlinx.coroutines.runBlocking
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import kotlinx.datetime.Clock
import kotlinx.io.bytestring.ByteString
import org.bouncycastle.util.encoders.Base64
import java.net.URLEncoder
import java.nio.charset.Charset
import kotlin.random.Random
import kotlin.time.Duration.Companion.days
import kotlin.time.DurationUnit

class AdminServlet : BaseHttpServlet() {
    companion object {
        private const val TAG = "AdminServlet"

        private const val PASSWORD_SALT = "1CxCucFhcIzcbMnSrIgB"
        private val AUTH_VALIDITY_DURATION = 7.days
        private val LIST_HEAD = "<!DOCTYPE html><html><head></head><body><ul>"
        private val LIST_TAIL = "</ul></body></html>"
        private val TABLE_HEAD = "<!DOCTYPE html><html><head><link rel='stylesheet' href='table.css'/></head><body><table>"
        private val TABLE_TAIL = "</table></body></html>"

        private lateinit var adminPasswordHash: ByteString

        private lateinit var stateCipher: SimpleCipher

        @Synchronized
        private fun updateAdminPasswordHash(hash: ByteString) {
            adminPasswordHash = hash
        }

        private fun saltedHash(password: String): ByteString {
            return ByteString(Crypto.digest(Algorithm.SHA256, "$PASSWORD_SALT$password".toByteArray()))
        }
    }

    override fun initializeEnvironment(env: FlowEnvironment): FlowNotifications? {
        val storage = env.getInterface(Storage::class)!!
        val messageEncryptionKey = runBlocking {
            val key = storage.get("RootState", "", "adminStateEncryptionKey")
            if (key != null) {
                key.toByteArray()
            } else {
                val newKey = Random.nextBytes(16)
                storage.insert(
                    "RootState",
                    "",
                    ByteString(newKey),
                    "adminStateEncryptionKey")
                newKey
            }
        }
        stateCipher = AesGcmCipher(messageEncryptionKey)
        adminPasswordHash = runBlocking {
            // Don't write initial password hash in the storage
            storage.get("RootState", "", "adminPasswordHash")
                ?: saltedHash(servletConfig.getInitParameter("initialAdminPassword"))
        }
        // Use notifications from FlowServlet (it must be initialized before AdminServlet)
        return environmentFor(FlowServlet::class)!!.getInterface(FlowNotifications::class)
    }

    private fun getAuthCookie(req: HttpServletRequest): Cookie? {
        if (req.cookies != null) {
            for (cookie in req.cookies) {
                if (cookie.name == "Auth") {
                    return cookie
                }
            }
        }
        return null
    }

    private fun adminAuthCheck(req: HttpServletRequest, resp: HttpServletResponse): Boolean {
        val cookie = getAuthCookie(req)
        if (cookie != null) {
            try {
                val parsedCookie =
                    AdminAuthCookie.fromCbor(stateCipher.decrypt(Base64.decode(cookie.value)))
                if (parsedCookie.expiration >= Clock.System.now()
                    && parsedCookie.passwordHash == adminPasswordHash) {
                    return true
                }
                Logger.e(TAG, "Expired or stale Auth cookie: ${parsedCookie.expiration}")
            } catch (err: Exception) {
                Logger.e(TAG, "Error parsing Auth cookie", err)
            }
        }
        resp.sendRedirect("login.html")
        return false
    }

    override fun doPost(req: HttpServletRequest, resp: HttpServletResponse) {
        val threadId = Thread.currentThread().id
        val remoteHost = getRemoteHost(req)
        val prefix = "tid=$threadId host=$remoteHost"
        val requestLength = req.contentLength
        Logger.i(TAG, "$prefix: POST ${req.pathInfo} ($requestLength bytes)")
        val action = req.pathInfo.substring(1)
        if (action != "login" && !adminAuthCheck(req, resp)) {
            return
        }
        val parameters = req.parameterMap
        when (action) {
            "updateDocument" -> {
                val clientId = parameters["clientId"]!![0]
                val issuingAuthorityId = parameters["issuingAuthorityId"]!![0]
                val documentId = parameters["documentId"]!![0]
                val newNumber = parameters["administrativeNumber"]!![0]
                val issuingAuthority = IssuingAuthorityState(clientId, issuingAuthorityId)
                runBlocking {
                    issuingAuthority.administrativeActionUpdateAdministrativeNumber(
                        env = environment,
                        documentId = documentId,
                        administrativeNumber = newNumber
                    )
                }
                resp.contentType = "text/plain"
                resp.writer.println("Success")
            }
            "login" -> {
                val password = parameters["password"]!![0]
                val cookie = getAuthCookie(req)
                if (cookie != null) {
                    cookie.maxAge = 0  // remove existing cookie if present
                }
                if (saltedHash(password) == adminPasswordHash) {
                    val expiration = Clock.System.now() + AUTH_VALIDITY_DURATION
                    val auth = Base64.toBase64String(stateCipher.encrypt(
                        AdminAuthCookie(expiration, adminPasswordHash).toCbor()))
                    val newCookie = Cookie("Auth", auth)
                    newCookie.path = "${req.contextPath}/"
                    newCookie.maxAge = AUTH_VALIDITY_DURATION.toInt(DurationUnit.SECONDS)
                    resp.addCookie(newCookie)
                    Logger.i(TAG, "Successful login")
                    resp.sendRedirect("index.html")
                } else {
                    Logger.e(TAG, "Incorrect password")
                    resp.sendRedirect("login.html")
                }
            }
            "password" -> {
                val oldPassword = parameters["oldPassword"]!![0]
                if (saltedHash(oldPassword) != adminPasswordHash) {
                    resp.contentType = "text/plain"
                    resp.writer.println("Old password is not correct")
                    return
                }
                val password = parameters["newPassword"]!![0]
                if (password != parameters["newPassword1"]!![0]) {
                    resp.contentType = "text/plain"
                    resp.writer.println("Passwords do not match")
                    return
                }
                val hash = saltedHash(password)
                adminPasswordHash = hash
                val storage = environment.getInterface(Storage::class)!!
                runBlocking {
                    if (storage.get("RootState", "", "adminPasswordHash") == null) {
                        storage.insert("RootState", "", hash,"adminPasswordHash")
                    } else {
                        storage.update("RootState", "", "adminPasswordHash", hash)
                    }
                }
                updateAdminPasswordHash(hash)
                resp.sendRedirect("login.html")
            }
            else -> {
                resp.sendError(404)
            }
        }
    }

    private fun htmlEscape(text: String?): String {
        return (text ?: "<null>")
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
    }

    override fun doGet(req: HttpServletRequest, resp: HttpServletResponse) {
        val threadId = Thread.currentThread().id
        val remoteHost = getRemoteHost(req)
        val prefix = "tid=$threadId host=$remoteHost"
        val rawPath = req.pathInfo ?: ""
        Logger.i(TAG, "$prefix: GET $rawPath")
        if (rawPath.isEmpty()) {
            resp.sendRedirect("admin/index.html")
            return
        }
        val path = rawPath.substring(1).ifEmpty { "index.html" }
        if (path != "login.html" && !adminAuthCheck(req, resp)) {
            return
        }
        when (path) {
            "documents.html" -> {
                val clientIds = req.parameterMap["clientId"]
                val clientId = if (clientIds == null) "" else clientIds[0]!!
                resp.contentType = "text/html; charset=utf-8"
                val writer = resp.outputStream.writer(Charset.forName("utf-8"))
                val storage = environment.getInterface(Storage::class)!!
                writer.write(TABLE_HEAD)
                writer.write("<tr><th>Id</th><th>Display Name</th></tr>")
                runBlocking {
                    val documentIds = storage.enumerate("IssuerDocument", clientId)
                    for (documentId in documentIds) {
                        writer.write("<tr>")
                        writer.write("<td class='code'>${htmlEscape(documentId)}</td>")
                        val documentData = storage.get("IssuerDocument", clientId, documentId)!!
                        val document = IssuerDocument.fromDataItem(Cbor.decode(documentData.toByteArray()))
                        writer.write("<td>${htmlEscape(document.documentConfiguration?.displayName)}</td>")
                        writer.write("<tr>")
                    }
                }
                writer.write(TABLE_TAIL)
                writer.flush()
            }
            "clients.html" -> {
                resp.contentType = "text/html; charset=utf-8"
                val writer = resp.outputStream.writer(Charset.forName("utf-8"))
                val storage = environment.getInterface(Storage::class)!!
                writer.write(LIST_HEAD)
                runBlocking {
                    val clients = storage.enumerate("ClientKeys", "")
                    for (client in clients) {
                        val escaped = htmlEscape(client)
                        val urlenc = URLEncoder.encode(client, "utf-8")
                        writer.write("<li><a href='documents.html?clientId=$urlenc'>$escaped</a></li>")
                    }
                }
                writer.write(LIST_TAIL)
                writer.flush()
            }
            else -> {
                val resources = environment.getInterface(Resources::class)!!
                val data = resources.getRawResource("www/$path")
                if (data == null) {
                    resp.sendError(404)
                } else {
                    val extension = path.substring(path.lastIndexOf(".") + 1)
                    resp.contentType = when (extension) {
                        "html" -> "text/html; charset=utf-8"
                        "jpeg", "jpg" -> "image/jpeg"
                        "png" -> "image/png"
                        "js" -> "application/javascript"
                        "css" -> "text/css"
                        else -> "application/octet-stream"
                    }
                    resp.outputStream.write(data.toByteArray())
                }
            }
        }
    }
}