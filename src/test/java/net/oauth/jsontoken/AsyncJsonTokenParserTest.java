package net.oauth.jsontoken;

import static org.junit.Assert.assertThrows;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import net.oauth.jsontoken.crypto.SignatureAlgorithm;
import net.oauth.jsontoken.discovery.AsyncVerifierProvider;
import net.oauth.jsontoken.discovery.AsyncVerifierProviders;
import net.oauth.jsontoken.exceptions.ErrorCode;
import net.oauth.jsontoken.exceptions.InvalidJsonTokenException;
import org.junit.function.ThrowingRunnable;

public class AsyncJsonTokenParserTest extends JsonTokenTestBase {

  private AsyncVerifierProviders asyncLocators;
  private AsyncVerifierProviders asyncLocatorsFromRuby;
  private Executor executor;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    AsyncVerifierProvider hmacLocator = (issuer, keyId) -> Futures.immediateFuture(
        locators.getVerifierProvider(SignatureAlgorithm.HS256).findVerifier(issuer, keyId));
    AsyncVerifierProvider rsaLocator = (issuer, keyId) -> Futures.immediateFuture(
        locators.getVerifierProvider(SignatureAlgorithm.RS256).findVerifier(issuer, keyId));

    asyncLocators =
        alg -> {
          if (alg.equals(SignatureAlgorithm.HS256)) {
            return hmacLocator;
          } else if (alg.equals(SignatureAlgorithm.RS256)) {
            return rsaLocator;
          }
          return null;
        };

    AsyncVerifierProvider hmacLocatorFromRuby = (issuer, keyId) -> Futures.immediateFuture(
        locatorsFromRuby.getVerifierProvider(SignatureAlgorithm.HS256).findVerifier(issuer, keyId));

    asyncLocatorsFromRuby =
        alg -> {
          if (alg.equals(SignatureAlgorithm.HS256)) {
            return hmacLocatorFromRuby;
          }
          return null;
        };

    executor = MoreExecutors.directExecutor();
  }

  public void testVerify_valid() throws Exception {
    AsyncJsonTokenParser parser = getAsyncJsonTokenParser();
    JsonToken checkToken = naiveDeserialize(TOKEN_STRING);
    parser.verify(checkToken).get();
  }

  public void testVerify_badSignature() throws Exception {
    AsyncJsonTokenParser parser = getAsyncJsonTokenParser();
    JsonToken checkToken = naiveDeserialize(TOKEN_STRING_BAD_SIG);
    assertFailsWithErrorCode(
        ErrorCode.BAD_SIGNATURE,
        () -> parser.verify(checkToken).get()
    );
  }

  public void testVerify_unsupportedSignature() throws Exception {
    AsyncJsonTokenParser parser = getAsyncJsonTokenParser();
    JsonToken checkToken = naiveDeserialize(TOKEN_STRING_UNSUPPORTED_SIGNATURE_ALGORITHM);
    assertFailsWithErrorCode(
        ErrorCode.UNSUPPORTED_ALGORITHM,
        () -> parser.verify(checkToken).get()
    );
  }

  public void testVerify_noVerifiers() throws Exception {
    AsyncVerifierProvider noLocator = (signerId, keyId) -> Futures.immediateFuture(null);
    AsyncVerifierProviders noLocators = alg -> {
      if (alg.equals(SignatureAlgorithm.HS256)) {
        return noLocator;
      }
      return null;
    };

    AsyncJsonTokenParser parser = getAsyncJsonTokenParser(noLocators, new AlwaysPassChecker());
    JsonToken checkToken = naiveDeserialize(TOKEN_STRING);
    assertFailsWithErrorCode(
        ErrorCode.NO_VERIFIER,
        () -> parser.verify(checkToken).get()
    );
  }

  public void testVerify_noProviders() throws Exception {
    AsyncVerifierProviders noProviders = alg -> null;
    AsyncJsonTokenParser parser = getAsyncJsonTokenParser(noProviders, new AlwaysPassChecker());
    JsonToken checkToken = naiveDeserialize(TOKEN_STRING);
    assertFailsWithErrorCode(
        ErrorCode.UNSUPPORTED_ALGORITHM,
        () -> parser.verify(checkToken).get()
    );
  }

  public void testVerifyAndDeserialize_valid() throws Exception {
    AsyncJsonTokenParser parser = getAsyncJsonTokenParser();
    JsonToken token = parser.verifyAndDeserialize(TOKEN_STRING).get();
    assertHeader(token);
    assertPayload(token);
  }

  public void testVerifyAndDeserialize_deserializeFail() throws Exception {
    AsyncJsonTokenParser parser = getAsyncJsonTokenParser();
    assertFailsWithErrorCode(
        ErrorCode.MALFORMED_TOKEN_STRING,
        () -> parser.verifyAndDeserialize(TOKEN_STRING_2PARTS).get()
    );
  }

  public void testVerifyAndDeserialize_verifyFail() throws Exception {
    AsyncJsonTokenParser parser = getAsyncJsonTokenParser();
    assertFailsWithErrorCode(
        ErrorCode.BAD_SIGNATURE,
        () -> parser.verifyAndDeserialize(TOKEN_STRING_BAD_SIG).get()
    );
  }

  public void testVerifyAndDeserialize_tokenFromRuby() throws Exception {
    AsyncJsonTokenParser parser =
        getAsyncJsonTokenParser(asyncLocatorsFromRuby, new AlwaysPassChecker());
    JsonToken token = parser.verifyAndDeserialize(TOKEN_FROM_RUBY).get();

    assertEquals(SignatureAlgorithm.HS256, token.getSignatureAlgorithm());
    assertEquals("JWT", token.getHeader().get(JsonToken.TYPE_HEADER).getAsString());
    assertEquals("world", token.getParamAsPrimitive("hello").getAsString());
  }

  public void testRethrowException_jsonParse() throws Exception {
    AsyncJsonTokenParser parser =
        getAsyncJsonTokenParser(asyncLocatorsFromRuby, new AlwaysPassChecker());
    InvalidJsonTokenException e = assertFailsWithErrorCode(
        ErrorCode.MALFORMED_TOKEN_STRING,
        () -> parser.verifyAndDeserialize(TOKEN_STRING_CORRUPT_HEADER).get()
    );

    assertNotNull(e.getCause());
    assertTrue(JsonParseException.class.isInstance(e.getCause()));
  }

  public void testRethrowException_unknownCause() throws Exception {
    AsyncJsonTokenParser parser = getAsyncJsonTokenParser(asyncLocators, new AlwaysFailChecker());
    JsonToken checkToken = naiveDeserialize(TOKEN_STRING);
    assertFailsWithErrorCode(
        ErrorCode.UNKNOWN,
        () -> parser.verify(checkToken).get()
    );
  }

  /**
   * Ensure that legitimate runtime exceptions get through.
   */
  public void testRethrowException_runtime() throws Exception {
    AsyncJsonTokenParser parser = getAsyncJsonTokenParser(asyncLocators, new AlwaysPassChecker());
    JsonToken checkToken = new JsonToken(new JsonObject());
    ExecutionException e = assertThrows(
        ExecutionException.class,
        () -> parser.verify(checkToken).get()
    );

    assertNotNull(e.getCause());
    assertTrue(IllegalStateException.class.isInstance(e.getCause()));
  }

  private AsyncJsonTokenParser getAsyncJsonTokenParser() {
    return new AsyncJsonTokenParser(clock, asyncLocators, executor, new AlwaysPassChecker());
  }

  private AsyncJsonTokenParser getAsyncJsonTokenParser(
      AsyncVerifierProviders providers, Checker... checkers) {
    return new AsyncJsonTokenParser(clock, providers, executor, checkers);
  }

  private InvalidJsonTokenException assertFailsWithErrorCode(ErrorCode errorCode, ThrowingRunnable runnable) {
    ExecutionException executionException = assertThrows(ExecutionException.class, runnable);

    Throwable e = executionException.getCause();
    assertTrue(InvalidJsonTokenException.class.isInstance(e));

    InvalidJsonTokenException invalidJsonTokenException = (InvalidJsonTokenException) e;
    assertTrue(invalidJsonTokenException.getErrorCode().equals(errorCode));
    return invalidJsonTokenException;
  }

}
