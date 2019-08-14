package com.onkiup.linker.parser;

import java.io.Reader;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

// at 0.2.2:
// - added "C" type parameter
// - replaced all "evaluate" flags with context
public class TokenGrammar<C, X extends Rule<C>> {
  private Class<X> type;

  public static <CC, XX extends Rule<CC>> TokenGrammar<CC, XX> forClass(Class<XX> type) {
    return new TokenGrammar<>(type);
  }

  protected TokenGrammar(Class<X> type) {
    this.type = type;
  }

  public static boolean isConcrete(Class<?> test) {
    return !(test.isInterface() || Modifier.isAbstract(test.getModifiers()));
  }

  public Class<X> getTokenType() {
    return type;
  }

  public X parse(Reader source) throws SyntaxError {
    return parse(source, null);
  }

  public X parse(Reader source, C context)  throws SyntaxError {
    X result = tokenize(source, context);
    StringBuilder tail = new StringBuilder();
    try {
      int nextChar;
      while (-1 != (nextChar = source.read())) {
        tail.append((char) nextChar);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }

    if (tail.length() > 0) {
      throw new RuntimeException("Unmatched trailing symbols: '" + tail + "'");
    }
    return result;
  }

  public X tokenize(Reader source, C context) throws SyntaxError {
    try {
      ParserState state = new ParserState(source, "");
      state.push(new PartialToken<C, X>(type, state.location()));
      TokenTestResult testResult = null;
      boolean hitEnd = false;

      do {
        PartialToken<C, ?> token = state.token();
        Class type = token.getTokenType();
        Field field = token.getCurrentField();
        TokenMatcher matcher = token.getMatcher();

        if (!state.empty()) {
          if (matcher == null) {
            if (type.isArray()) {
              state.push(new PartialToken(type.getComponentType(), state.location()));
              continue;
            } if (!isConcrete(type)) {
              Class variant = token.getCurrentAlternative();
              state.push(new PartialToken(variant, state.location()));
              continue;
            } else if (Rule.class.isAssignableFrom(type)) {
              if (field == null) {
                throw new RuntimeException(type + " field is null \n" + state);
              }
              Class fieldType = field.getType();
              PartialToken child;
              if (fieldType.isArray()) {
                child = new PartialToken(fieldType, state.location());
              } else {
                child = new PartialToken(field.getType(), state.location());
                if (String.class.isAssignableFrom(fieldType)) {
                  if (Modifier.isStatic(field.getModifiers())) {
                    field.setAccessible(true);
                    String pattern = (String) field.get(null);
                    child.setMatcher(new TerminalMatcher(pattern));
                  } else if (field.isAnnotationPresent(CapturePattern.class)) {
                    CapturePattern pattern = field.getAnnotation(CapturePattern.class);
                    child.setMatcher(new PatternMatcher(pattern));
                  }
                } else if (Number.class.isAssignableFrom(fieldType)) {
                   child.setMatcher(new NumberMatcher(fieldType));
                } else if (!Rule.class.isAssignableFrom(fieldType)) {
                  throw new RuntimeException("Unsupported token type: '"+ fieldType +"'");
                }
              }
              state.push(child);
              continue;
            } else {
              throw new RuntimeException(type + " should implement com.onkiup.linker.parser.Rule interface; ");
            }
          } else {
            testResult = matcher.apply(state.buffer());
            if (testResult.isMatch()) {
              Object value = testResult.getToken();
              token.finalize(value.toString());
              state.drop(testResult.getTokenLength());
            } else if (testResult.isFailed()){
              state.pop();

              PartialToken parent;
              boolean pickedAlternative = false;
              while (null != (parent = state.pop())) {
                if (parent.hasAlternativesLeft()) {
                   parent.advanceAlternative(state.lineSource());
                   state.push(parent);
                   pickedAlternative = true;
                   break;
                 } else if (parent.isFieldOptional()) {
                   parent.populateField(null);
                   state.push(parent);
                   pickedAlternative = true;
                   break;
                 }
              }

              if (!pickedAlternative) {
                if (parent == null) {
                  throw new SyntaxError("Expected " + type + " with matcher " + matcher, state);
                } else {
                  throw new SyntaxError("Expected " + parent, state);
                }
              }
            }
          }
        } 

        if (!state.advance()) {
          hitEnd = true; 
          if (!token.isPopulated() && !state.empty() && testResult != null && testResult.isMatchContinue()) {
            Object value = testResult.getToken();
            token.finalize((String)value);
            state.drop(testResult.getTokenLength());
          } 
        }

        // tracing back
        while (token.isPopulated() || (hitEnd && !token.hasRequiredFields())) {
          state.pop();
          if (Rule.class.isAssignableFrom(type) || type.isArray()) {
            if (isConcrete(type) || type.isArray()) {
              token.finalize(context);
            }
          } 

          if (state.depth() == 0) {
            // SUCESS? 
            return (X) token.getToken();
          }
 
          Object value = token.getToken();
          PartialToken<C, ?> parent = (PartialToken<C, ?>) state.token();
          Class parentType = parent.getTokenType();
          if (parentType.isArray()) {
            parent.add(value);
          } else if (Rule.class.isAssignableFrom(parentType)) {
            if (isConcrete(parentType)) {
              parent.populateField(value); 
            } else {
              parent.resolve(value);
            }
          }

         token = parent;
         type = token.getTokenType();
        }
      } while(!state.empty());

      throw new SyntaxError("Unexpected end of input", state);
    } catch (SyntaxError se) {
      throw se;
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}

