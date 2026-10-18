"""Pure fixed Redis ACL contract; hashes are private input and private output."""
import re

QUOTA_COMMANDS = ('ping', 'hello', 'client|setinfo', 'client|setname', 'eval',
                  'time', 'info|memory', 'exists', 'get', 'set', 'incr', 'decr',
                  'del', 'hget', 'hset', 'zadd', 'zrange', 'zrem')
SESSION_COMMANDS = ('hgetall', 'hkeys', 'hdel', 'pexpireat', 'sadd', 'srem',
                    'smembers', 'scard')
PATTERNS = {'authorization': ['authorization:admin-quota:v1:*'],
            'identity': ['identity:quota:v1:*'], 'webbff': ['web-bff:*']}
USERS = (*PATTERNS, 'health', 'monitor')


def acl_file(password_hashes):
    if (not isinstance(password_hashes, dict) or set(password_hashes) != set(USERS)
            or any(not isinstance(value, str) or not re.fullmatch('[a-f0-9]{64}', value)
                   for value in password_hashes.values())
            or len(set(password_hashes.values())) != len(USERS)):
        raise ValueError('five distinct password hashes required')
    lines = ['user default reset off']
    for user in USERS:
        commands = QUOTA_COMMANDS if user in PATTERNS else ('ping',)
        if user == 'webbff': commands += SESSION_COMMANDS
        elif user == 'monitor': commands += ('info|memory', 'info|persistence', 'info|stats')
        rules = [f'user {user} reset on #{password_hashes[user]}', '-@all']
        rules.extend('~' + pattern for pattern in PATTERNS.get(user, []))
        rules.extend('+' + command for command in commands)
        lines.append(' '.join(rules))
    return '\n'.join(lines) + '\n'
