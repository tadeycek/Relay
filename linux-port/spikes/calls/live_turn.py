"""Opt-in Cloudflare TURN/TLS443 probe. Never prints credentials or native diagnostics."""
import json
from datetime import datetime, timezone
import os
from pathlib import Path
import re
import subprocess
import urllib.error
import urllib.parse
import urllib.request

HERE = Path(__file__).resolve().parent
CONFIG = HERE.parents[2] / '.local/realtime.json'
ORIGIN = 'https://rtc.live.cloudflare.com'
URL = 'turns:turn.cloudflare.com:443?transport=tcp'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('Provider redirects forbidden')


def configuration(env):
    if env.get('RELAY_SPIKE_LIVE') != '1':
        raise ValueError('Explicit RELAY_SPIKE_LIVE=1 opt-in required')
    if CONFIG.stat().st_mode & 0o077:
        raise ValueError('Provider configuration must be private')
    config = json.loads(CONFIG.read_text())
    for key in ('TURN_KEY_ID', 'TURN_API_TOKEN'):
        value = config.get(key)
        if not isinstance(value, str) or not re.fullmatch(r'[A-Za-z0-9_-]{8,512}', value):
            raise ValueError('Invalid provider configuration')
    return config


def request(config, suffix, body):
    req = urllib.request.Request(
        ORIGIN + '/v1/turn/keys/' + config['TURN_KEY_ID'] + suffix,
        data=json.dumps(body).encode(), method='POST',
        headers={'Authorization': 'Bearer ' + config['TURN_API_TOKEN'], 'Content-Type': 'application/json',
                 'User-Agent': 'Relay-WP02-Probe/1.0'})
    try:
        with urllib.request.build_opener(NoRedirect()).open(req, timeout=20) as response:
            data = response.read(65537)
            if len(data) > 65536:
                raise ValueError('Provider response exceeds bound')
            return response.status, json.loads(data) if data else None
    except urllib.error.HTTPError as e:
        raise ValueError('Provider HTTP status ' + str(e.code)) from None
    except (OSError, urllib.error.URLError):
        raise ValueError('Provider request failed; details suppressed') from None


def select_credentials(response):
    servers = response.get('iceServers')
    if isinstance(servers, dict):
        servers = [servers]
    if not isinstance(servers, list):
        raise ValueError('Provider ICE response missing servers')
    for server in servers:
        urls = server.get('urls', [])
        if isinstance(urls, str):
            urls = [urls]
        if URL in urls and all(isinstance(server.get(k), str) and server[k] for k in ('username', 'credential')):
            return server['username'], server['credential']
    raise ValueError('Provider did not issue TLS443 credentials')


def validate(text):
    if 'cleanup=COMPLETE' not in text:
        raise ValueError('Native cleanup missing')
    if 'data_messages=2 result=PASS mode=forced-turn-tls443' not in text:
        raise ValueError('Forced-relay native media exchange failed')
    match = re.search(r'^decoded_audio a_pcm_bytes=(\d+) a_nonzero_bytes=(\d+) b_pcm_bytes=(\d+) b_nonzero_bytes=(\d+)$', text, re.M)
    if not match or not all(int(n) > 0 for n in match.groups()):
        raise ValueError('Both native receivers must decode nonzero PCM')
    # Follow transport.selectedCandidatePairId; never infer selection from gathered candidates.
    pairs = re.findall(r'^selected_pair side=([ab]) local=relay remote=relay relay_protocol=tls sent=(\d+) received=(\d+)$', text, re.M)
    if {p[0] for p in pairs} != {'a', 'b'} or not all(int(p[1]) > 0 and int(p[2]) > 0 for p in pairs):
        raise ValueError('Both selected candidate pairs must prove active TURN/TLS')
    return {'mode': 'forced-turn-tls443', 'synthetic_audio': True, 'physical_audio': False,
            'host_firewall_changed': False, 'independent_udp_block_test': False,
            'decoded_pcm': dict(zip(('a_bytes', 'a_nonzero_bytes', 'b_bytes', 'b_nonzero_bytes'), map(int, match.groups()))),
            'selected_pairs': [{'side': side, 'local': 'relay', 'remote': 'relay', 'relay_protocol': 'tls',
                                'sent': int(sent), 'received': int(received)} for side, sent, received in pairs]}


def main():
    config = configuration(os.environ)
    started = datetime.now(timezone.utc).isoformat()
    java_home = Path(os.environ.get('JAVA_HOME', str(Path.home() / 'tools/jdk-21.0.12.1+1')))
    private = HERE / '.private'
    private.mkdir(mode=0o700, exist_ok=True)
    os.chmod(private, 0o700)
    classes = private / 'turn-classes'
    classes.mkdir(mode=0o700, exist_ok=True)
    jars = str(HERE / 'build/install/relay-calls-spike/lib/*')
    # No Gradle daemon receives the ephemeral credentials. Native/API output remains private.
    log = private / 'live-turn-native.log'
    fd = os.open(log, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as stream:
        subprocess.run([str(java_home / 'bin/javac'), '-cp', jars, '-d', str(classes),
                        str(HERE / 'src/main/java/relay/spike/CallsProbe.java')],
                       stdout=stream, stderr=subprocess.STDOUT, check=True, timeout=60)
    status, response = request(config, '/credentials/generate-ice-servers', {'ttl': 600})
    if status != 201:
        raise ValueError('Unexpected credential creation status')
    username = None
    result = None
    try:
        username, password = select_credentials(response)
        env = dict(os.environ, RELAY_SPIKE_LIVE='1', RELAY_TURN_URL=URL,
                   RELAY_TURN_USERNAME=username, RELAY_TURN_PASSWORD=password)
        env.pop('RELAY_SPIKE_BODY_DIR', None)  # Never export raw live SDP/ICE via a local-size-test option.
        with log.open('a') as stream:
            completed = subprocess.run([str(java_home / 'bin/java'), '-cp', str(classes) + os.pathsep + jars,
                                        'relay.spike.CallsProbe', '--turn'], env=env,
                                       stdout=stream, stderr=subprocess.STDOUT, timeout=90)
        if completed.returncode:
            raise ValueError('Native TURN probe failed; diagnostic retained privately')
        result = validate(log.read_text())
    finally:
        if username is not None:
            code, _ = request(config, '/credentials/' + urllib.parse.quote(username, safe='') + '/revoke', {})
            if code != 204:
                raise ValueError('Ephemeral credential revocation not confirmed')
    result['credentials_revoked_http'] = 204
    result['run_started_utc'] = started
    result['run_completed_utc'] = datetime.now(timezone.utc).isoformat()
    target = HERE / 'evidence/live-turn.json'
    target.write_text(json.dumps(result, indent=2, sort_keys=True) + '\n')
    print(json.dumps(result, sort_keys=True))


if __name__ == '__main__':
    try:
        main()
    except (Exception, KeyboardInterrupt) as error:
        # Provider/native exception details can include secret values; never echo them.
        print('live_turn=FAIL type=' + type(error).__name__ + ' details_suppressed=true')
        raise SystemExit(1)
