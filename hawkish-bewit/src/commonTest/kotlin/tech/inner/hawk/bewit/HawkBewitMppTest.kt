package tech.inner.hawk.bewit

import com.chrynan.uri.core.Uri
import com.chrynan.uri.core.fromString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class HawkBewitMppTest {
  private val creds1 = HawkCredentials(
    keyId = "9aA4bFc9df",
    key = "4fDE242CacAFdEAFcb5e5b44CFfd7cf4adD53A4AfF32CF5deD7A92facDEC4b33",
    algorithm = HawkCredentials.Algorithm.SHA256,
  )
  private val creds2 = HawkCredentials(
    keyId = "545D9dC9d7",
    key = "32fe2DAF2DE9DcC5AE434Aa7C24CFae3ed42dad3eCe7CED5abf443fbbDFfcAdA",
    algorithm = HawkCredentials.Algorithm.SHA256,
  )
  private val uri1 = Uri.fromString("https://localhost:1111/abc")
  private val uri1DiffScheme = Uri.fromString("http://localhost:1111/abc")
  private val uri1DiffPath = Uri.fromString("https://localhost:1111/abcd")
  private val uri1DiffHost = Uri.fromString("https://otherhost:1111/abc")
  private val uri1DiffPort = Uri.fromString("https://localhost:1112/abc")

  private val uri2 = Uri.fromString("https://localhost:1111/testpath/subpath?param1=val1&param2=val2")
  private val uri2DiffQuery = Uri.fromString("https://localhost:1111/testpath/subpath?param1=val1&param2=val3")

  private val uri3DefaultPort = Uri.fromString("https://localhost/abc")
  private val uri3SetPort = Uri.fromString("https://localhost:444/abc")

  private val clockSeed = Instant.parse("2022-01-25T05:00:00.000Z")
  private val testClock = Clock.Fixed(clockSeed)

  @Test
  fun validBewitIsGoodAndHasCorrectExpiry() {
    with(HawkBewit(testClock)) {
      val ttl = 10.minutes
      val bewit = generate(creds1, uri1, testClock.now() + ttl)
      assertEquals(validate(creds1, uri1, bewit), BewitValidationResult.Good((clockSeed + ttl)))
    }
  }

  @Test
  fun expiredBewitRaisesExpiredResult() {
    with(HawkBewit(testClock)) {
      val ttl = 1.minutes
      val bewit = generate(creds1, uri1, testClock.now() + ttl)
      testClock.fastForward(2.minutes)
      assertEquals(validate(creds1, uri1, bewit), BewitValidationResult.Expired((clockSeed + ttl)))
    }
  }

  @Test
  fun authenticationFailsOnEnvelopeExpiryChange() {
    with(HawkBewit(testClock)) {
      val ttl = 1.minutes
      val bewit = generate(creds1, uri1, testClock.now() + ttl)
      testClock.fastForward(2.minutes)

      // update to non-expired manually, but only in envelope
      // should fail but now with a MAC mismatch, as the MAC is based on the envelope timestamp
      val bewitUpdatedExpiry = bewit.base64UrlToBytes()!!
        .decodeToString()
        .split("\\")
        .toMutableList()
        .apply {
          set(1, clockSeed.plus(3.minutes).epochSeconds.toString())
        }
        .joinToString("\\")
        .encodeToByteArray()
        .toBase64Url()

      assertEquals(
        validate(creds1, uri1, bewitUpdatedExpiry),
        BewitValidationResult.AuthenticationError("MAC mismatch"),
      )
    }
  }

  @Test
  fun authenticationFailsUriMismatch() {
    with(HawkBewit(testClock)) {
      val ttl = 1.minutes
      val bewit = generate(creds1, uri1, testClock.now() + ttl)
      assertEquals(
        validate(creds1, uri2, bewit),
        BewitValidationResult.AuthenticationError("MAC mismatch")
      )
    }
  }

  @Test
  fun authenticationFailsKeyIdMismatch() {
    with(HawkBewit(testClock)) {
      val ttl = 1.minutes
      val bewit = generate(creds1, uri1, testClock.now() + ttl)
      assertEquals(
        validate(creds1.copy(keyId = "abc"), uri1, bewit),
        BewitValidationResult.Bad("Key id mismatch")
      )
    }
  }

  @Test
  fun authenticationFailsCredentialsMismatch() {
    with(HawkBewit(testClock)) {
      val ttl = 1.minutes
      val bewit = generate(creds1, uri1, testClock.now() + ttl)
      assertEquals(
        validate(creds2, uri1, bewit),
        BewitValidationResult.Bad("Key id mismatch")
      )
    }
  }

  @Test
  fun authenticationFailsNoCredentialsForKeyId() {
    with(HawkBewit(testClock)) {
      val ttl = 1.minutes
      val bewit = generate(creds1, uri1, testClock.now() + ttl)
      assertEquals(
        validate(uri1, bewit) { null },
        BewitValidationResult.Bad("No credentials for key id 9aA4bFc9df")
      )
    }
  }

  @Test
  fun authenticationSucceedsCredentialsLookupByKeyId() {
    with(HawkBewit(testClock)) {
      val ttl = 1.minutes
      val bewit = generate(creds1, uri1, testClock.now() + ttl)
      assertEquals(
        validate(uri1, bewit) { k -> if (k == creds1.keyId) creds1 else null },
        BewitValidationResult.Good((clockSeed + ttl))
      )
    }
  }

  @Test
  fun authenticationFailsMissingComponents() {
    with(HawkBewit(testClock)) {
      val ttl = 1.minutes
      val bewit = generate(creds1, uri1, testClock.now() + ttl)
      val bewitNoKey = bewit.base64UrlToBytes()!!.decodeToString()
        .split("\\")
        .drop(1)
        .joinToString("\\")
        .encodeToByteArray()
        .toBase64Url()

      assertEquals(
        validate(creds1, uri1, bewitNoKey),
        BewitValidationResult.Bad("Invalid bewit")
      )
    }
  }

  @Test
  fun authenticationFailsMissingMac() {
    with(HawkBewit(testClock)) {
      val ttl = 1.minutes
      val bewit = generate(creds1, uri1, testClock.now() + ttl)
      val bewitNoMac = bewit.base64UrlToBytes()!!.decodeToString()
        .split("\\")
        .dropLast(2)
        .plus("")
        .plus("")
        .joinToString("\\")
        .encodeToByteArray()
        .toBase64Url()

      assertEquals(
        validate(creds1, uri1, bewitNoMac),
        BewitValidationResult.AuthenticationError("MAC mismatch")
      )
    }
  }

  @Test
  fun authenticationFailsPathHostPortMismatch() {
    with(HawkBewit(testClock)) {
      val ttl = 1.minutes
      val bewit = generate(creds1, uri1, testClock.now() + ttl)

      assertEquals(
        validate(creds1, uri1DiffPath, bewit),
        BewitValidationResult.AuthenticationError("MAC mismatch")
      )
      assertEquals(
        validate(creds1, uri1DiffHost, bewit),
        BewitValidationResult.AuthenticationError("MAC mismatch")
      )
      assertEquals(
        validate(creds1, uri1DiffPort, bewit),
        BewitValidationResult.AuthenticationError("MAC mismatch")
      )
    }
  }

  @Test
  fun authenticationFailsDefaultPortMismatch() {
    with(HawkBewit(testClock)) {
      val ttl = 1.minutes
      val bewit = generate(creds1, uri3DefaultPort, testClock.now() + ttl)

      assertEquals(
        validate(creds1, uri3SetPort, bewit),
        BewitValidationResult.AuthenticationError("MAC mismatch")
      )
    }
  }

  @Test
  fun authenticationFailsSchemeMismatch() {
    with(HawkBewit(testClock)) {
      val ttl = 1.minutes
      val bewit = generate(creds1, uri1, testClock.now() + ttl)

      assertEquals(
        validate(creds1, uri1DiffScheme, bewit),
        BewitValidationResult.AuthenticationError("MAC mismatch")
      )
    }
  }

  @Test
  fun authenticationFailsQueryParamsMismatch() {
    with(HawkBewit(testClock)) {
      val ttl = 1.minutes
      val bewit = generate(creds1, uri2, testClock.now() + ttl)

      assertEquals(
        validate(creds1, uri2DiffQuery, bewit),
        BewitValidationResult.AuthenticationError("MAC mismatch")
      )
    }
  }
}
