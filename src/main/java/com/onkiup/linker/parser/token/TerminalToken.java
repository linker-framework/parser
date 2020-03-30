package com.onkiup.linker.parser.token;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.util.Optional;

import com.onkiup.linker.parser.MatcherFactory;
import com.onkiup.linker.parser.ParserLocation;
import com.onkiup.linker.parser.TokenMatcher;
import com.onkiup.linker.util.LoggerLayout;

/**
 * A PartialToken used to populate non-rule tokens
 */
public class TerminalToken extends AbstractToken<String> implements ConsumingToken<String>, Serializable {
  private transient TokenMatcher matcher;
  private CharSequence token;

  public TerminalToken(CompoundToken parent, int position, Field field, Class tokenType, ParserLocation location) {
    super(parent, position, field, location);
    this.matcher = MatcherFactory.forField(parent, field, tokenType);

    this.setTokenMatcher(matcher);
  }

  @Override
  public void onConsumeSuccess(Object token) {
    log("MATCHED '{}'", LoggerLayout.sanitize((String)token));
    this.token = (String) token;
  }

  @Override
  public Optional<String> token() {
    return Optional.ofNullable(token).map(CharSequence::toString);
  }

  @Override
  public Class<String> tokenType() {
    return String.class;
  }
}

