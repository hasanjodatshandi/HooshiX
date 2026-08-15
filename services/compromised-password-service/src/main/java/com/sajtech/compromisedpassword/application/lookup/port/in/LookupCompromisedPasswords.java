package com.sajtech.compromisedpassword.application.lookup.port.in;

import com.sajtech.compromisedpassword.domain.lookup.valueobject.CompromisedHashMatch;
import com.sajtech.compromisedpassword.domain.lookup.valueobject.Sha1Prefix;
import java.util.List;

public interface LookupCompromisedPasswords {
    List<CompromisedHashMatch> lookup(Sha1Prefix prefix);
}
