package com.sajtech.compromisedpassword.application.lookup.port.out;

import com.sajtech.compromisedpassword.domain.lookup.valueobject.CompromisedHashMatch;
import com.sajtech.compromisedpassword.domain.lookup.valueobject.Sha1Prefix;
import java.util.List;

public interface CompromisedPasswordRepository {
  List<CompromisedHashMatch> findByPrefix(Sha1Prefix prefix);
}
