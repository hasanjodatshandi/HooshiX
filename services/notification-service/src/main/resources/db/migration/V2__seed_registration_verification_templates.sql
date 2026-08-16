INSERT INTO notification_template_definition(definition_id, channel, semantic_type, locale) VALUES
    ('11111111-1111-4111-8111-111111111101', 'EMAIL', 'REGISTRATION_VERIFICATION_CODE', 'en'),
    ('11111111-1111-4111-8111-111111111102', 'EMAIL', 'REGISTRATION_VERIFICATION_CODE', 'fa'),
    ('11111111-1111-4111-8111-111111111103', 'SMS', 'REGISTRATION_VERIFICATION_CODE', 'en'),
    ('11111111-1111-4111-8111-111111111104', 'SMS', 'REGISTRATION_VERIFICATION_CODE', 'fa');

INSERT INTO notification_template_version(
    version_id, definition_id, version_number, state, content_sha256,
    subject_template, text_template, html_template
) VALUES
    (
        '22222222-2222-4222-8222-222222222201',
        '11111111-1111-4111-8111-111111111101',
        1,
        'PUBLISHED',
        '4dea370bd1dfc981055c6d9ca4f4a41644189f002ca9db54f149898ecd4c7ad1',
        'Verify your SajTech account',
        'Your verification code is {code}. It expires in {expires_minutes} minutes.',
        '<p>Your verification code is <strong>{code}</strong>.</p><p>It expires in {expires_minutes} minutes.</p>'
    ),
    (
        '22222222-2222-4222-8222-222222222202',
        '11111111-1111-4111-8111-111111111102',
        1,
        'PUBLISHED',
        'bf699110b73489187ced6cbd4cf709b81f777219c2a0093d2890424a0451d6a2',
        'تأیید حساب SajTech',
        'کد تأیید شما {code} است. این کد تا {expires_minutes} دقیقه معتبر است.',
        '<p>کد تأیید شما <strong>{code}</strong> است.</p><p>این کد تا {expires_minutes} دقیقه معتبر است.</p>'
    ),
    (
        '22222222-2222-4222-8222-222222222203',
        '11111111-1111-4111-8111-111111111103',
        1,
        'PUBLISHED',
        '49a5f9a76884bc5fc289b1bf3a8fd71647290fa55ff99596e1aa5824719fbd0e',
        NULL,
        'Your SajTech verification code is {code}. It expires in {expires_minutes} minutes.',
        NULL
    ),
    (
        '22222222-2222-4222-8222-222222222204',
        '11111111-1111-4111-8111-111111111104',
        1,
        'PUBLISHED',
        '76c5d6d65f3d60de926fd2c003a5ae6d0d9dcd5370b71c07f84a7622120cfd34',
        NULL,
        'کد تأیید SajTech شما {code} است. اعتبار: {expires_minutes} دقیقه.',
        NULL
    );

INSERT INTO notification_template_activation(definition_id, active_version_id, generation) VALUES
    ('11111111-1111-4111-8111-111111111101', '22222222-2222-4222-8222-222222222201', 1),
    ('11111111-1111-4111-8111-111111111102', '22222222-2222-4222-8222-222222222202', 1),
    ('11111111-1111-4111-8111-111111111103', '22222222-2222-4222-8222-222222222203', 1),
    ('11111111-1111-4111-8111-111111111104', '22222222-2222-4222-8222-222222222204', 1);

INSERT INTO notification_template_audit(event_id, definition_id, version_id, action) VALUES
    ('33333333-3333-4333-8333-333333333301', '11111111-1111-4111-8111-111111111101', '22222222-2222-4222-8222-222222222201', 'INITIAL_ACTIVATION'),
    ('33333333-3333-4333-8333-333333333302', '11111111-1111-4111-8111-111111111102', '22222222-2222-4222-8222-222222222202', 'INITIAL_ACTIVATION'),
    ('33333333-3333-4333-8333-333333333303', '11111111-1111-4111-8111-111111111103', '22222222-2222-4222-8222-222222222203', 'INITIAL_ACTIVATION'),
    ('33333333-3333-4333-8333-333333333304', '11111111-1111-4111-8111-111111111104', '22222222-2222-4222-8222-222222222204', 'INITIAL_ACTIVATION');
