package tech.inner.hawk.bewit

import com.chrynan.uri.core.Uri
import com.chrynan.uri.core.fromString
import okio.Buffer
import kotlin.time.Clock
import kotlin.time.Instant

sealed class BewitValidationResult {
  data class Bad(val message: String): BewitValidationResult()
  data class Expired(val expiry: Instant): BewitValidationResult()
  data class AuthenticationError(val message: String): BewitValidationResult()
  data class Good(val expiry: Instant): BewitValidationResult()
}

/**
 * Create a HawkBewit to generate and validate signed URLs. The Clock used for expiry is optionally provided
 * in the constructor to [assist with unit tests](https://stackoverflow.com/a/56026271/430128).
 *
 * It is the caller's responsibility to add the bewit to the link after generation, and then extract and remove
 * the bewit from the signed link before validation. This library makes no assumptions about where in the signed
 * URL the bewit is stored, as long as the unsigned URI passed to [generate] is the same as the unsigned URI
 * passed here. The Hawk spec states the bewit should be a query parameter, but we find it often works well as a
 * path parameter instead for better compatibility with tools that display links inline (e.g. email clients) and
 * applications like Microsoft Word that appear to strip query parameters in their latest incarnations.
 * The bewit could even be sent out of band for certain use cases.
 */
class HawkBewit(private val clock: Clock = Clock.System) {
  companion object {
    // not compatible with Hawk directly, we validate scheme as well
    const val HAWK_VERSION = "1a"
    const val AUTH_TYPE_BEWIT = "BEWIT"

    private const val DEFAULT_HTTP_PORT = 80
    private const val DEFAULT_HTTPS_PORT = 443
    private const val BEWIT_FIELDS = 4
    private const val BEWIT_FIELD_ID = 0
    private const val BEWIT_FIELD_EXPIRY = 1
    private const val BEWIT_FIELD_MAC = 2
  }

  private data class BewitData(
    val keyId: String,
    val expiry: Instant,
    @Suppress("ArrayInDataClass") val mac: ByteArray,
  )

  /**
   * Generate a bewit, which uses an HMAC to create a signature based on the URI and expiry. It is the
   * responsibility of the caller to "stuff" the bewit into the URL in whatever mechanism makes sense for the
   * caller. The spec states the bewit should be a query parameter, but it is the caller's responsibility to
   * handle the bewit (see the docs for [HawkBewit]).
   */
  fun generate(
    credentials: HawkCredentials,
    uri: Uri,
    expiry: Instant,
  ): String {
    val macBase64 = calculateMac(
      credentials,
      expiry,
      uri,
    ).toBase64Url()

    val bewit = buildString {
      append(credentials.keyId)
      append('\\')
      append(expiry.epochSeconds)
      append('\\')
      append(macBase64)
      append('\\')
    }
    return bewit.encodeToByteArray().toBase64Url()
  }

  /**
   * Given credentials and a URI (unstuffed of any bewit), and the bewit itself, validate that the bewit is valid
   * for the provided URI.
   *
   * The bewit is provided as a parameter rather than extracted from the passed [URI]. The spec states the bewit
   * should be a query parameter, but it is the caller's responsibility to handle the bewit (see the docs for
   * [HawkBewit]). This library makes no assumptions about where the bewit was stored between [generate] and this
   * call.
   */
  fun validate(credentials: HawkCredentials, uri: Uri, bewit: String): BewitValidationResult =
    validate(uri, bewit) { credentials }

  /**
   * Given credentials and a URI (unstuffed of any bewit), and the bewit itself, validate that the bewit is valid
   * for the provided URI.
   *
   * The bewit is provided as a parameter rather than extracted from the passed [URI]. The spec states the bewit
   * should be a query parameter, but it is the caller's responsibility to handle the bewit (see the docs for
   * [HawkBewit]). This library makes no assumptions about where the bewit was stored between [generate] and this
   * call.
   *
   * This version of [validate] obtains credentials from the passed in function which is provided the key id
   * present in the Bewit -- this allows the caller to use a different credential for each key id. If the
   * credentials function returns null, the Bewit is considered invalid.
   */
  @Suppress("ReturnCount", "MemberVisibilityCanBePrivate")
  fun validate(uri: Uri, bewit: String, credentialsFn: (String) -> HawkCredentials?): BewitValidationResult {
    val bewitData = try {
      decodeBewit(bewit)
    } catch (e: IllegalStateException) {
      return BewitValidationResult.Bad(e.message ?: "Illegal bewit format")
    }

    val credentials = credentialsFn(bewitData.keyId)
      ?: return BewitValidationResult.Bad("No credentials for key id ${bewitData.keyId}")

    if (credentials.keyId != bewitData.keyId) {
      return BewitValidationResult.Bad("Key id mismatch")
    }

    if (clock.now() > bewitData.expiry) {
      return BewitValidationResult.Expired(bewitData.expiry)
    }

    val calculatedMac = calculateMac(
      credentials,
      bewitData.expiry,
      uri,
    )

    // TODO use a constant-time algorithm to avoid timing attacks like JVM's MessageDigest.isEqual
    // https://codahale.com/a-lesson-in-timing-attacks/
    if (!calculatedMac.contentEquals(bewitData.mac)) {
      return BewitValidationResult.AuthenticationError("MAC mismatch")
    }

    return BewitValidationResult.Good(bewitData.expiry)
  }

  /**
   * Create an unsigned URI for input into Hawk. This is different from `URI(url)` in two respects: firstly, it does
   * not set the port value, which may not matter for bewits anyway. Secondly, and more importantly, the URI single-arg
   * constructor does not accept unencoded URLs i.e. it will fail on paths with spaces with a `URISyntaxException`.
   *
   * Also note URI encodes paths differently than URLEncoder -- URI encodes spaces with %20 and URLEncoder encodes them
   * with +. Since the encoding must be consistent with validation time, we use URL to parse the components of the
   * URLEncoder-encoded data, then decode the path before passing to the five-arg constructor of URI, which expects
   * decoded data and encodes it (though the docs seem to indicate it shouldn't, so hopefully we aren't relying on a
   * bug here).
   *
   * @param url The encoded URL string.
   */
  fun hawkUnsignedUri(url: String): Uri =
    Uri.fromString(url)

  private fun calculateMac(
    credentials: HawkCredentials,
    timestamp: Instant,
    uri: Uri,
  ): ByteArray {
    val hawkString = buildString(1024) {
      append("hawk.")
      append(HAWK_VERSION)
      append('.')
      append(AUTH_TYPE_BEWIT)
      append('\n')
      append(timestamp.epochSeconds)
      append('\n')
      append('\n')
      append("GET")
      append('\n')
      append(uri.path)
      if (uri.query != null) {
        append('?')
        append(uri.query)
      }
      // add scheme to the auth, not part of the original hawk spec
      append('\n')
      append(uri.scheme.lowercase())
      append('\n')
      append(uri.host?.lowercase() ?: "")
      append('\n')
      append(uriPort(uri))
    }

    return calculateMac(credentials, hawkString)
  }

  private fun calculateMac(credentials: HawkCredentials, text: String): ByteArray {
    val plaintextBuffer = Buffer().write(text.encodeToByteArray())
    val keyBuffer = Buffer().write(credentials.key.encodeToByteArray())

    val hmac = when (credentials.algorithm) {
      HawkCredentials.Algorithm.SHA1 -> plaintextBuffer.hmacSha1(keyBuffer.readByteString())
      HawkCredentials.Algorithm.SHA256 -> plaintextBuffer.hmacSha256(keyBuffer.readByteString())
    }

    return hmac.toByteArray()
  }

  private fun decodeBewit(bewit: String): BewitData {
    val decodedBewit = bewit.base64UrlToBytes()?.decodeToString() ?: error("Invalid bewit")
    val bewitFields = decodedBewit.split('\\')
    if (bewitFields.size != BEWIT_FIELDS) error("Invalid bewit")
    return BewitData(
      keyId = bewitFields[BEWIT_FIELD_ID],
      expiry = Instant.fromEpochSeconds(bewitFields[BEWIT_FIELD_EXPIRY].toLong()),
      mac = bewitFields[BEWIT_FIELD_MAC].base64UrlToBytes() ?: error("Invalid bewit"),
    )
  }

  private fun uriPort(uri: Uri): Int {
    fun defaultPort() = when (uri.scheme) {
      "http" -> DEFAULT_HTTP_PORT
      "https" -> DEFAULT_HTTPS_PORT
      else -> error("Unknown URI scheme \"" + uri.scheme + "\"")
    }

    return when (val p = uri.port) {
      null -> defaultPort()
      -1 -> defaultPort()
      else -> p
    }
  }
}
