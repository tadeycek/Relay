"""Bounded Cloudflare credential exchange; never logs response bodies or secrets."""
import json
from pathlib import Path
import time
import urllib.request
import urllib.error
import urllib.parse

URL = 'turns:turn.cloudflare.com:443?transport=tcp'
TTL = 600


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError('Provider redirects forbidden')



def revoke(username):
    """Revoke only the ephemeral username issued for this bounded run."""
    try:
        config = json.loads((Path(__file__).resolve().parents[3] / '.local/realtime.json').read_text())
        request = urllib.request.Request(
            'https://rtc.live.cloudflare.com/v1/turn/keys/' + config['TURN_KEY_ID'] + '/credentials/' + urllib.parse.quote(username, safe='') + '/revoke',
            data=b'{}', method='POST',
            headers={'Authorization': 'Bearer ' + config['TURN_API_TOKEN'], 'Content-Type': 'application/json', 'User-Agent': 'Relay-WP02-Probe/1.0'})
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=20) as response:
            if response.status != 204:
                raise ValueError('revocation not confirmed')
        return 204
    except Exception:
        raise RuntimeError('TURN revocation not confirmed; bounded TTL remains (sensitive details suppressed)') from None


def generate():
    path = Path(__file__).resolve().parents[3] / '.local/realtime.json'
    try:
        config = json.loads(path.read_text())
        key = config['TURN_KEY_ID']
        token = config['TURN_API_TOKEN']
        # IDs are path components, never arbitrary URLs.
        assert isinstance(key, str) and key and all(c.isalnum() or c in '-_' for c in key)
        request = urllib.request.Request(
            f'https://rtc.live.cloudflare.com/v1/turn/keys/{key}/credentials/generate-ice-servers',
            data=json.dumps({'ttl': TTL}).encode(), method='POST',
            headers={'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json', 'User-Agent': 'Relay-WP02-Probe/1.0'})
        issued = time.time()
        with urllib.request.build_opener(NoRedirect()).open(request, timeout=20) as response:
            body = response.read(65537)
        assert len(body) <= 65536
        servers = json.loads(body)['iceServers']
        if isinstance(servers, dict):
            servers = [servers]
        for server in servers:
            urls = server.get('urls', [])
            if isinstance(urls, str):
                urls = [urls]
            if URL in urls:
                user, password = server['username'], server['credential']
                assert all(isinstance(v, str) and 0 < len(v) <= 4096 and '\n' not in v and '\r' not in v for v in (user, password))
                return (URL, user, password), issued + TTL
        raise ValueError('required TLS443 endpoint missing')
    except urllib.error.HTTPError as error:
        raise RuntimeError(f'TURN credential generation failed http_status={int(error.code)} (sensitive details suppressed)') from None
    except Exception:
        raise RuntimeError('TURN credential generation failed (sensitive details suppressed)') from None
