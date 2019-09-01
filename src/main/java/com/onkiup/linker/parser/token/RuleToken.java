package com.onkiup.linker.parser.token;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.onkiup.linker.parser.Rule;
import com.onkiup.linker.parser.SyntaxError;
import com.onkiup.linker.parser.annotation.IgnoreCharacters;

public class RuleToken<X extends Rule> implements PartialToken<X> {
  private static final Logger logger = LoggerFactory.getLogger(RuleToken.class);
  private X token;
  private Class<X> tokenType;
  private Field[] fields;
  private PartialToken[] values;
  private PartialToken<? extends Rule> parent;
  private String ignoreCharacters = ""; 
  private int nextField;
  private final int position;
  private int nextTokenPosition;
  private boolean rotated = false;

  public RuleToken(PartialToken<? extends Rule> parent, Class<X> type, int position) {
    this.tokenType = type;
    this.parent = parent;
    this.position = position;
    this.nextTokenPosition = position;

    try {
      this.token = type.newInstance();
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to instantiate rule token " + type, e);
    }

    fields = Arrays.stream(type.getDeclaredFields())
      .filter(field -> !Modifier.isTransient(field.getModifiers()))
      .toArray(Field[]::new);

    values = new PartialToken[fields.length];

    if (parent != null) {
      ignoreCharacters += parent.getIgnoredCharacters();
    }

    if (type.isAnnotationPresent(IgnoreCharacters.class)) {
      IgnoreCharacters annotation = type.getAnnotation(IgnoreCharacters.class);
      if (!annotation.inherit()) {
        ignoreCharacters = "";
      }
      ignoreCharacters += type.getAnnotation(IgnoreCharacters.class).value();
    }
  }

  @Override
  public Optional<StringBuilder> pushback(boolean force) {
    logger.info("Pushing {} back", this);
    StringBuilder result = new StringBuilder();
    Field lastField = fields[nextField - 1];

    if (force || !isOptional(lastField)) {
      int backField;
      for (backField = nextField - 1; backField > -1; backField--) {
        PartialToken token = values[backField];
        Field field = fields[backField];

        logger.debug("Trying to rotate token {}", token);
        if (token.rotatable()) {
          token.rotate();
          logger.debug("Rotated token {}", token);
          rotated = true;
          break;
        }

        if (token.alternativesLeft() > 0) {
          logger.debug("Found alternatives at field {}.{}", tokenType.getSimpleName(), field.getName());
          token.pushback(false).ifPresent(result::append);
          break;
        }
      }

      if (backField < 0) {
        logger.debug("Failed token {}, pushing parent", this);
        getParent()
          .flatMap(p -> p.pushback(false))
          .ifPresent(b -> result.append(b));
        return Optional.of(result);
      }

      for (int i = backField + 1; i < nextField - 1; i++) {
        Field field = fields[i];
        logger.debug("{}: pulling back field {}.{}", this, tokenType.getSimpleName(), field.getName());
        values[i].pullback().ifPresent(b -> {
          logger.debug("{}: pulled back '{}' from {}.{}", this, b, tokenType.getSimpleName(), field.getName());
          result.append(b);
        });
      }

      nextField = backField + 1;

      return Optional.of(result);
    } else {
      logger.debug("Not propagating pushback call from optional field");
      return values[nextField - 1].pullback();
    }
  }

  @Override
  public Optional<StringBuilder> pullback() {
    logger.debug("Pullback request received by {}", this);
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < nextField; i++) {
      PartialToken token = values[i];
      if (token == null) {
        continue;
      }
      Field field = fields[i];
      logger.debug("{}: Pulling back token {} from field {}.{}", this, token, field.getDeclaringClass().getSimpleName(), field.getName());
      token.pullback().ifPresent(b -> {
        logger.debug("{}: pulled back: '{}'", RuleToken.this, b);
        result.append(b);
      });
    }
    return Optional.of(result);
  }

  @Override
  public Optional<PartialToken> advance(boolean force) throws SyntaxError {

    if (nextField > 0) {
      // checking if the last token was successfully populated
      int currentField = nextField - 1;
      Field field = fields[currentField];
      PartialToken token = values[currentField];
      logger.debug("{}: Verifying results for field {}", this, field.getName());

      if (rotated) {
        logger.info("Last token was rotated, not advancing");
        rotated = false;
        return Optional.of(token);
      } if (token != null && token.isPopulated()) {
        set(field, token.getToken());
        nextTokenPosition += token.consumed();
      } else if (token != null && token.alternativesLeft() > 0) {
        logger.debug("last token still has some alternatives, not advancing");
        return Optional.of(token);
      } else if (token != null && !isOptional(field)){
        if (parent == null) {
          throw new SyntaxError("Expected " + field);
        } else {
          return parent == null ? Optional.empty() : parent.advance(force);
        }
      }
    }

    if (force && nextField < fields.length) {
      logger.debug("Force-advancing token {}", token);
      // checking if all unpopulated fields are optional
      // and fast-forwarding to populate this token if possible
      do {
        Field field = fields[nextField];
        if (!field.isAnnotationPresent(com.onkiup.linker.parser.annotation.Optional.class)) {
          break;
        }
        set(field, null);
      } while (++nextField < fields.length);

      if (isPopulated()) {
        logger.debug("Force-populated token {}", this);
        sortPriorities();
        return parent == null ? Optional.empty() : parent.advance(force);
      } 

      logger.debug("{}: Force-populate failed", this);
      return pushback(false).map(b -> new FailedToken(parent, b, position));
    }

    if (nextField < fields.length && nextField > -1) {
      Field field = fields[nextField];
      logger.info("Populating field {}.{}", tokenType.getSimpleName(), field.getName());
      return Optional.of(values[nextField++] = createChild(field, nextTokenPosition));
    }

    logger.debug("Populated token {}", this);
    sortPriorities();
    return parent == null ? Optional.empty() : parent.advance(force);
  }

  protected PartialToken createChild(Field field, int position) {
    return PartialToken.forField(this, field, position);
  }

  @Override
  public void sortPriorities() {
    if (rotatable()) {
      PartialToken child = values[0];
      if (child != null && child.rotatable()) {
        int myPriority = basePriority();
        int childPriority = child.basePriority();
        logger.debug("Verifying priority order for tokens; parent: {}({}) child: {}({})", this, myPriority, child, childPriority);
        if (childPriority < myPriority) {
          logger.debug("Fixing priority order between {} and {}", this, child);
          unrotate();
        }
      }
    }
  }

  @Override
  public X getToken() {
    return token;
  }

  @Override 
  public Class<X> getTokenType() {
    return tokenType;
  }

  @Override
  public Optional<PartialToken> getParent() {
    return Optional.ofNullable(parent);
  }

  @Override
  public boolean isPopulated() {
    for (int i = values.length - 1; i > -1; i--) {
      PartialToken lastToken = values[i];
      if (lastToken != null && lastToken.isPopulated()) {
        return true;
      }
      Field field = fields[i];
      if (!isOptional(field)) {
        return false;
      }
    }
    // empty class? 
    return true;
  }

  @Override
  public String getIgnoredCharacters() {
    return ignoreCharacters;
  }

  private void set(Field field, Object value) {
    try {
      if (!Modifier.isStatic(field.getModifiers())) {
        logger.debug("Setting field {}.{} to '{}'", field.getDeclaringClass().getSimpleName(), field.getName(), value);
        field.setAccessible(true);
        field.set(token, convert(field.getType(), value));
        try {
          token.reevaluate();
        } catch (Exception e) {
          logger.warn(this + ": Failed to reevaluate", e);
        }
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to populate field " + field, e);
    }
  }

  private boolean isOptional(Field field) throws SyntaxError {
    return field.isAnnotationPresent(com.onkiup.linker.parser.annotation.Optional.class);
  }

  protected <T> T convert(Class<T> into, Object what) { 
    if (into.isArray()) {
      Object[] collection = (Object[]) what;
      T[] result = (T[]) Array.newInstance(into.getComponentType(), collection.length);
      int i = 0;
      for (Object item : collection) {
        result[i++] = (T)item;
      }
      return (T) result;
    }


    if (what == null || into.isAssignableFrom(what.getClass())) {
      return (T) what;
    }

    try {
      Constructor<T> constructor = into.getConstructor(String.class);
      constructor.setAccessible(true);
      return constructor.newInstance(what.toString());
    } catch (Exception e) {
      // nothiing to do
    }

    try {
      Method converter = into.getMethod("fromString", String.class);
      converter.setAccessible(true);
      return (T) converter.invoke(null, what.toString());
    } catch (Exception e) {
      // nothiing to do
    }

    try {
      Method converter = into.getMethod("valueOf", String.class);
      converter.setAccessible(true);
      return (T) converter.invoke(null, what.toString());
    } catch (Exception e) {
      throw new RuntimeException("Unable to convert '" + what + "' into " + into, e);
    }
  }

  @Override
  public String toString() {
    return "RuleToken(" + token + ")@[" + position + " - " + (position + consumed()) + "]";
  }

  @Override
  public int position() {
    return position;
  }

  @Override
  public int consumed() {
    int consumedByLastToken = 0;
    if (nextField > 0 && values[nextField - 1] != null) {
      consumedByLastToken = values[nextField - 1].consumed();
    }
    return nextTokenPosition - position + consumedByLastToken;
  }

  @Override
  public boolean rotatable() {
    if (fields.length < 3) {
      logger.debug("{}: Not rotatable -- not enough fields", this);
      return false;
    }

    if (!this.isPopulated()) {
      logger.debug("{}: Not rotatable -- not populated", this);
      return false;
    }

    Field field = fields[0];
    Class fieldType = field.getType();
    if (!fieldType.isAssignableFrom(tokenType)) {
      logger.debug("{}: Not rotatable -- first field is not assignable from token type", this);
      return false;
    }

    field = fields[fields.length - 1];
    fieldType = field.getType();
    if (!fieldType.isAssignableFrom(tokenType)) {
      logger.debug("{}: Not rotatable -- last field is not assignable from token type", this);
      return false;
    }

    return true;
  }

  @Override
  public void rotate() {
    logger.info("Rotating token {}", this);
    token.invalidate();
    RuleToken wrap = new RuleToken(this, tokenType, position);
    wrap.nextField = nextField;
    nextField = 1;
    PartialToken[] wrapValues = wrap.values;
    wrap.values = values;
    values = wrapValues;
    values[0] = wrap;
    X wrapToken = (X) wrap.token;
    wrap.token = token;
    token = wrapToken;
    setChildren(values);
  }

  @Override
  public void unrotate() {
    logger.debug("Un-rotating token {}", this);
    PartialToken firstToken = values[0];
    PartialToken kiddo = firstToken;
    if (kiddo instanceof VariantToken) {
      kiddo = ((VariantToken)kiddo).resolvedAs();
    }

    Rule childToken = (Rule) kiddo.getToken();
    Class childTokenType = kiddo.getTokenType();

    invalidate();
    kiddo.invalidate();

    PartialToken[] grandChildren = kiddo.getChildren();
    values[0] = grandChildren[grandChildren.length - 1];
    set(fields[0], values[0].getToken());
    kiddo.setToken(token);
    kiddo.setChildren(values);

    values = grandChildren;
    values[values.length - 1] = kiddo;
    tokenType = null;
    token = (X) childToken;
    tokenType = (Class<X>) childTokenType;
    setChildren(values);
    set(fields[fields.length - 1], values[values.length - 1].getToken());
  }

  @Override
  public PartialToken[] getChildren() {
    return values;
  }

  @Override
  public void setToken(X token) {
    this.token = token;
  }

  @Override
  public void setChildren(PartialToken[] children) {
    this.values = children;
  }

  @Override
  public void invalidate() {
    token.invalidate();
  }
}
