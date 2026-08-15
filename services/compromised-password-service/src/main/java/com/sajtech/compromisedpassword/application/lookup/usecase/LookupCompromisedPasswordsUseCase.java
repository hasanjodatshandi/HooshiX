package com.sajtech.compromisedpassword.application.lookup.usecase;

import com.sajtech.compromisedpassword.application.lookup.port.in.LookupCompromisedPasswords;
import com.sajtech.compromisedpassword.application.lookup.port.out.CompromisedPasswordRepository;
import com.sajtech.compromisedpassword.domain.lookup.valueobject.CompromisedHashMatch;
import com.sajtech.compromisedpassword.domain.lookup.valueobject.Sha1Prefix;
import java.util.List;
import java.util.Objects;

public final class LookupCompromisedPasswordsUseCase implements LookupCompromisedPasswords {
  private final CompromisedPasswordRepository repository;

  public LookupCompromisedPasswordsUseCase(CompromisedPasswordRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  @Override
  public List<CompromisedHashMatch> lookup(Sha1Prefix prefix) {
    return repository.findByPrefix(Objects.requireNonNull(prefix, "prefix"));
  }
}
