ALTER TABLE notification_template_definition
    DROP CONSTRAINT notification_template_definition_semantic_type_check;

ALTER TABLE notification_template_definition
    ADD CONSTRAINT notification_template_definition_semantic_type_check CHECK (
        semantic_type IN (
            'REGISTRATION_VERIFICATION_CODE',
            'CONTACT_VERIFICATION_CODE',
            'PASSWORD_RECOVERY_CODE',
            'MFA_VERIFICATION_CODE',
            'PASSWORD_CHANGED_NOTICE'
        )
    );

ALTER TABLE notification
    DROP CONSTRAINT notification_semantic_type_valid;

ALTER TABLE notification
    ADD CONSTRAINT notification_semantic_type_valid CHECK (
        semantic_type IN (
            'REGISTRATION_VERIFICATION_CODE',
            'CONTACT_VERIFICATION_CODE',
            'PASSWORD_RECOVERY_CODE',
            'MFA_VERIFICATION_CODE',
            'PASSWORD_CHANGED_NOTICE'
        )
    );

INSERT INTO notification_template_definition(definition_id, channel, semantic_type, locale) VALUES
    ('11111111-1111-4111-8111-111111111105', 'EMAIL', 'CONTACT_VERIFICATION_CODE', 'en'),
    ('11111111-1111-4111-8111-111111111106', 'EMAIL', 'CONTACT_VERIFICATION_CODE', 'fa'),
    ('11111111-1111-4111-8111-111111111107', 'SMS', 'CONTACT_VERIFICATION_CODE', 'en'),
    ('11111111-1111-4111-8111-111111111108', 'SMS', 'CONTACT_VERIFICATION_CODE', 'fa');

INSERT INTO notification_template_version(
    version_id, definition_id, version_number, state, content_sha256,
    subject_template, text_template, html_template
) VALUES
    ('22222222-2222-4222-8222-222222222205', '11111111-1111-4111-8111-111111111105', 1,
     'PUBLISHED', '8b9fd6864ad48cfcb74fdea0a56a500245545e7a40092623cd41be7cbf34c81d',
     'Verify your SajTech account',
     'Your verification code is {code}. It expires in {expires_minutes} minutes.',
     '<p>Your verification code is <strong>{code}</strong>.</p><p>It expires in {expires_minutes} minutes.</p>'),
    ('22222222-2222-4222-8222-222222222206', '11111111-1111-4111-8111-111111111106', 1,
     'PUBLISHED', 'd9ce4d1c4cb2406c5a7faaaf3400c6d2ca102dad6fbb790c25d5057773b9eac5',
     'تأیید حساب SajTech',
     'کد تأیید شما {code} است. این کد تا {expires_minutes} دقیقه معتبر است.',
     '<p>کد تأیید شما <strong>{code}</strong> است.</p><p>این کد تا {expires_minutes} دقیقه معتبر است.</p>'),
    ('22222222-2222-4222-8222-222222222207', '11111111-1111-4111-8111-111111111107', 1,
     'PUBLISHED', '5eb6de082d7de40183c7903c861466617b5d4da5722bd70d72d1d36b68d6acd1',
     NULL, 'Your SajTech verification code is {code}. It expires in {expires_minutes} minutes.', NULL),
    ('22222222-2222-4222-8222-222222222208', '11111111-1111-4111-8111-111111111108', 1,
     'PUBLISHED', 'a6f0da70a11c2e530c26ddbf7ad28f3d1212a7e25acfbc1b0f6ea0db8cd24bc6',
     NULL, 'کد تأیید SajTech شما {code} است. اعتبار: {expires_minutes} دقیقه.', NULL);

INSERT INTO notification_template_activation(definition_id, active_version_id, generation) VALUES
    ('11111111-1111-4111-8111-111111111105', '22222222-2222-4222-8222-222222222205', 1),
    ('11111111-1111-4111-8111-111111111106', '22222222-2222-4222-8222-222222222206', 1),
    ('11111111-1111-4111-8111-111111111107', '22222222-2222-4222-8222-222222222207', 1),
    ('11111111-1111-4111-8111-111111111108', '22222222-2222-4222-8222-222222222208', 1);

INSERT INTO notification_template_audit(event_id, definition_id, version_id, action) VALUES
    ('33333333-3333-4333-8333-333333333305', '11111111-1111-4111-8111-111111111105', '22222222-2222-4222-8222-222222222205', 'INITIAL_ACTIVATION'),
    ('33333333-3333-4333-8333-333333333306', '11111111-1111-4111-8111-111111111106', '22222222-2222-4222-8222-222222222206', 'INITIAL_ACTIVATION'),
    ('33333333-3333-4333-8333-333333333307', '11111111-1111-4111-8111-111111111107', '22222222-2222-4222-8222-222222222207', 'INITIAL_ACTIVATION'),
    ('33333333-3333-4333-8333-333333333308', '11111111-1111-4111-8111-111111111108', '22222222-2222-4222-8222-222222222208', 'INITIAL_ACTIVATION');
