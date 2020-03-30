package com.onkiup.linker.parser;

import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.lang.reflect.Field;
import java.util.Objects;
import java.util.function.Predicate;

import com.onkiup.linker.parser.annotation.OptionalToken;
import com.onkiup.linker.parser.annotation.SkipIfFollowedBy;
import com.onkiup.linker.parser.token.CollectionToken;
import com.onkiup.linker.parser.token.CompoundToken;
import com.onkiup.linker.parser.token.EnumToken;
import com.onkiup.linker.parser.token.PartialToken;
import com.onkiup.linker.parser.token.RuleToken;
import com.onkiup.linker.parser.token.TerminalToken;
import com.onkiup.linker.parser.token.VariantToken;
import com.onkiup.linker.parser.util.ParserError;
import com.onkiup.linker.util.TypeUtils;

/**
 * @author : chedim (chedim@chedim-Surface-Pro-3)
 * @file : TokenFactory
 * @created : Tuesday Mar 17, 2020 12:26:30 EDT
 */

public final class TokenFactory {
  private TokenFactory() {

  }

  /**
   * Creates a new CompoundToken for the provided class
   * 
   * @param type     class for which new token should be created
   * @param position position at which the token will be located in the parser's
   *                 input
   * @return created CompoundToken
   */
  public static CompoundToken forClass(Class<? extends Rule> type, int childNumber, ParserLocation position) {
    if (position == null) {
      position = new ParserLocation(null, 0, 0, 0);
    }
    if (TypeUtils.isConcrete(type)) {
      return new RuleToken(null, childNumber, null, type, position);
    } else {
      return new VariantToken(null, childNumber, null, type, position);
    }
  }

  /**
   * Creates a new PartialToken for provided field
   * 
   * @param parent   parent token
   * @param field    the field for which a new PartialToken should be created
   * @param position token position in parser's buffer
   * @return created PartialToken
   */
  public static PartialToken forField(CompoundToken parent, int childNumber, Field field, ParserLocation position) {
    if (position == null) {
      throw new ParserError("Child token position cannot be null", parent);
    }

    Class fieldType = field.getType();
    return forField(parent, childNumber, field, fieldType, position);
  }

  /**
   * Creates a new PartialToken of given type for given field
   * 
   * @param parent    parent token
   * @param field     field for which a new PartialToken will be created
   * @param tokenType the type of the resulting token
   * @param position  token position in parser's buffer
   * @param <X>
   * @return created PartialToken
   */
  public static <X> PartialToken<X> forField(CompoundToken parent, int childNumber, Field field, Class tokenType,
      ParserLocation position) {
    if (tokenType.isArray()) {
      return new CollectionToken(parent, childNumber, field, tokenType, position);
    } else if (Rule.class.isAssignableFrom(tokenType)) {
      if (!TokenGrammar.isConcrete(tokenType)) {
        return new VariantToken(parent, childNumber, field, tokenType, position);
      } else {
        return new RuleToken(parent, childNumber, field, tokenType, position);
      }
    } else if (tokenType == String.class) {
      return (PartialToken<X>) new TerminalToken(parent, childNumber, field, tokenType, position);
    } else if (tokenType.isEnum()) {
      return (PartialToken<X>) new EnumToken(parent, childNumber, field, tokenType, position);
    }
    throw new IllegalArgumentException("Unsupported field type: " + tokenType);
  }

  /**
   * @param field field to check for presence of OptionalToken or SkipIfFollowedBy
   *              annotations
   * @return true if the field is annotated with either {@link OptionalToken} or
   *         {@link SkipIfFollowedBy}
   */
  public static boolean hasOptionalAnnotation(Field field) {
    return field != null
        && (field.isAnnotationPresent(OptionalToken.class) || field.isAnnotationPresent(SkipIfFollowedBy.class));
  }

  /**
   * Context-aware field optionality checks
   * //TODO: move to utils
   * 
   * @param owner Context to check
   * @param field Field to check
   * @return true if the field should be optional in this context
   */
  public static boolean isOptional(CompoundToken owner, Field field) {
    try {
      if (field.isAnnotationPresent(OptionalToken.class)) {
        owner.log("Performing context-aware optionality check for field ${}", field);
        OptionalToken optionalToken = field.getAnnotation(OptionalToken.class);
        boolean result;
        if (optionalToken.whenFieldIsNull().length() != 0) {
          final String fieldName = optionalToken.whenFieldIsNull();
          result = testContextField(owner, fieldName, Objects::isNull);
          owner.log("whenFieldIsNull({}) == {}", fieldName, result);
        } else if (optionalToken.whenFieldNotNull().length() != 0) {
          final String fieldName = optionalToken.whenFieldNotNull();
          result = testContextField(owner, fieldName, Objects::nonNull);
          owner.log("whenFieldNotNull({}) == {}", fieldName, result);
        } else {
          result = optionalToken.whenFollowedBy().length() == 0;
          owner.log("No context-aware conditions found; isOptional = {}", result);
        }
        return result;
      }

      return false;
    } catch (Exception e) {
      throw new ParserError("Failed to determine if field " + field.getName() + " should be optional", owner);
    }
  }

  /**
   * Tests if given field has context-aware optionality condition and should be
   * optional in the current context
   * 
   * @param owner     the token that contains the field
   * @param fieldName the name of the field
   * @param tester    Predicate to use in the test
   * @return test result
   * @throws NoSuchFieldException
   * @throws IllegalAccessException
   */
  public static boolean testContextField(CompoundToken owner, String fieldName, Predicate<Object> tester)
      throws NoSuchFieldException, IllegalAccessException {
    Field targetField = owner.tokenType().getField(fieldName);
    targetField.setAccessible(true);
    boolean result = tester.test(targetField.get(owner.token()));
    return result;
  }

  /**
   * Loads previously serialized PartialToken from provided InputStream
   * 
   * @param is the InputStream to read a PartialToken from
   * @return deserialized PartialToken
   * @throws IOException
   * @throws ClassNotFoundException
   */
  public static PartialToken load(InputStream is) throws IOException, ClassNotFoundException {
    ObjectInputStream ois = new ObjectInputStream(is);
    Object result = ois.readObject();
    if (result instanceof PartialToken) {
      return (PartialToken) result;
    }
    String resultType = result == null ? "null" : result.getClass().getName();
    throw new IllegalArgumentException(resultType + " is not a PartialToken");
  }

}
