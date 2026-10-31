package dev.phonemcp.core;
import org.junit.Test;
import static org.junit.Assert.*;
public class AcceptReviewRegressionTest {
 @Test public void truncatedParameterFailsClosedWithoutThrowing() {
  assertFalse(AcceptHeader.acceptsMcp("application/json, text/event-stream;foo="));
 }
 @Test public void conflictingDuplicateDoesNotOverrideExplicitZero() {
  assertFalse(AcceptHeader.acceptsMcp("application/json;q=0, application/json, text/event-stream"));
 }
}
