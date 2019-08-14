package com.onkiup.linker.parser;

import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;

import org.junit.Assert;
import org.junit.Test;

public class TokenGrammarTest {

  public static interface Junction extends Rule<Object> {
  
  }

  public static class TestGrammarDefinition implements Junction {
    private static final String marker = ":";
    @CapturePattern(pattern = "[^;\\n]+")
    private String command;
  }

  public static class CommentGrammarDefinition implements Junction {
    private static final String marker = "//";
    @CapturePattern(pattern = "[^\\n]*")
    private String comment;
    @Optional
    private static final String tail = "\n";
  }

  public static class MultilineCommentGrammarDefinition implements Junction {
    private static final String marker = "/*";
    @CapturePattern(until="\\*/")
    private String comment;
    private static final String tail = "*/";
  }

  public static class StatementSeparator implements Junction {
    @CapturePattern(pattern = "[\\s\\n]*;[\\s\\n]*")
    private String value;
  }

  public static class ArrayToken implements Rule<Object> {
    @CaptureLimit(min=2, max=4)
    private Junction[] tokens = new Junction[3];
  }

  public static class Evaluatable implements Rule<Map<String, Object>> {
    private static final String VAR_MARKER = "$";
    @CapturePattern(pattern = "[^\\s\\n;]+")
    private String varName;

    @Override
    public void accept(Map<String, Object> context) {
      context.put(varName, context.get(varName) + "test");
    }
  }

  // bug in < 0.3.3
  public static class SubRuleGrammar implements Rule<Object> {
    private static String MARKER = "!";
    private TestGrammarDefinition command;
  }

  // bug in < 0.3.4
  public static class TestGrammarWithOptionalLastField implements Rule<Object> {
    private static final String marker = ":";
    @Optional
    @CapturePattern(pattern="[^\\s\\n]+")
    private String command;
  }

  // bug in < 0.3.4
  public static class OptionalGrammarWrapper implements Rule<Object> {
    private TestGrammarWithOptionalLastField test;
    private static final String space = " "; 
  }

  // bug in < 0.3.4
  @Test
  public void testOptionalEnding() throws Exception {
    TokenGrammar<?, OptionalGrammarWrapper> grammar = TokenGrammar.forClass(OptionalGrammarWrapper.class);

    OptionalGrammarWrapper result = grammar.parse(new StringReader(": "));
    Assert.assertNotNull(result);
    Assert.assertNotNull(result.test);
    Assert.assertEquals("", result.test.command);
  }

  // bug in < 0.3.3 
  @Test
  public void testSubRule() throws Exception {
    TokenGrammar<?, SubRuleGrammar> grammar = TokenGrammar.forClass(SubRuleGrammar.class);
    Assert.assertNotNull(grammar);

    SubRuleGrammar result = grammar.parse(new StringReader("!:test"));
    Assert.assertNotNull(result);
    Assert.assertNotNull(result.command);
    Assert.assertEquals("test", result.command.command);
  }

  @Test
  public void testGrammar() throws Exception {
    TokenGrammar<?, TestGrammarDefinition> grammar = TokenGrammar.forClass(TestGrammarDefinition.class);
    Assert.assertNotNull(grammar);
    TestGrammarDefinition token = grammar.parse(new StringReader(":test"));
    Assert.assertEquals("test", token.command);
  }

  @Test
  public void testTrailingCharactersException() throws Exception {
    TokenGrammar<?, TestGrammarDefinition> grammar = TokenGrammar.forClass(TestGrammarDefinition.class);
    Assert.assertNotNull(grammar);
    try {
      TestGrammarDefinition result = grammar.parse(new StringReader(":test; :another;"));
      Assert.fail();
    } catch (Exception e) {
      // this is expected
    }
  }

  @Test
  public void testJunction() throws Exception {
    TokenGrammar<Object, Junction> grammar = TokenGrammar.forClass(Junction.class);
    Assert.assertNotNull(grammar);

    Junction result = grammar.parse(new StringReader("// comment"));
    Assert.assertTrue(result instanceof CommentGrammarDefinition);
    CommentGrammarDefinition comment = (CommentGrammarDefinition) result;
    Assert.assertEquals(" comment", comment.comment);
  }

  @Test
  public void testCapture() throws Exception {
    TokenGrammar<Object, Junction> grammar = TokenGrammar.forClass(Junction.class);
    Assert.assertNotNull(grammar);

    Junction result = grammar.parse(new StringReader("/* comment */"));
    Assert.assertTrue(result instanceof MultilineCommentGrammarDefinition);
  }

  @Test
  public void testEvaluation() throws Exception {
    TokenGrammar<Map<String, Object>, Evaluatable> grammar = TokenGrammar.forClass(Evaluatable.class);

    Map<String, Object> context = new HashMap<>();
    context.put("test", 100);

    grammar.parse(new StringReader("$test"), context);
    Assert.assertEquals("100test", context.get("test"));
  }

  @Test
  public void testArrayCapture() throws Exception {
    TokenGrammar<Object, ArrayToken> grammar = TokenGrammar.forClass(ArrayToken.class);
    String test = ":hello; // comment\n/* multiline\ncomment */";

    ArrayToken result = grammar.parse(new StringReader(test));
    Assert.assertNotNull(result);
    Assert.assertNotNull(result.tokens);

    Junction[] tokens = result.tokens;
    Assert.assertEquals(4, tokens.length);
    
    Junction token = tokens[0];
    Assert.assertEquals(TestGrammarDefinition.class, token.getClass());
    Assert.assertEquals("hello", ((TestGrammarDefinition) token).command);

    token = tokens[1];
    Assert.assertEquals(StatementSeparator.class, token.getClass());

    token = tokens[2];
    Assert.assertEquals(CommentGrammarDefinition.class, token.getClass());
    Assert.assertEquals(" comment", ((CommentGrammarDefinition)token).comment);

    token = tokens[3];
    Assert.assertEquals(MultilineCommentGrammarDefinition.class, token.getClass());
    Assert.assertEquals(" multiline\ncomment ", ((MultilineCommentGrammarDefinition)token).comment);
  }

  @Test
  public void testArrayCaptureLimit() throws Exception {
    TokenGrammar<Object, ArrayToken> grammar = TokenGrammar.forClass(ArrayToken.class);
    try {
      ArrayToken result = grammar.parse(new StringReader(":test"));
      Assert.fail("CaptureLimit min is ignored");
    } catch (Exception e) {
      // this is expected
    }
    
    try {
      grammar.parse(new StringReader(":a;:b;:c;:d;:e;:f;:g"));
      Assert.fail("CaptureLimit max is ignored");
    } catch (Exception e) {
      // this is expected
    }
  }
}
