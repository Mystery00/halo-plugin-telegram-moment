"""在隔离的 Halo 与模拟 Telegram 中验证发布链路；凭据文件不写入仓库。"""
import argparse
import base64
import http.cookiejar
import json
import re
import time
import ssl
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import padding

parser = argparse.ArgumentParser()
parser.add_argument('--credentials', required=True)
parser.add_argument('--halo', default='http://127.0.0.1:18092')
parser.add_argument('--mock', default='https://127.0.0.1:18094')
parser.add_argument('--ca', default='workplace/store-test/tls/server.crt')
args = parser.parse_args()
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=args.ca)), urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))


def request(path, data=None, method=None, base=None):
    headers = {'Accept': 'application/json'}
    if data is not None:
        headers['Content-Type'] = 'application/json'
        data = json.dumps(data).encode()
    req = urllib.request.Request((base or args.halo) + path, data=data, headers=headers, method=method)
    with opener.open(req, timeout=90) as response:
        body = response.read()
        return json.loads(body) if body else None


def login():
    creds = json.loads(Path(args.credentials).read_text(encoding='utf-8-sig'))
    page = opener.open(args.halo + '/login').read().decode()
    public_key = json.loads('"' + re.search(r'const publicKey = "([^"]+)"', page).group(1) + '"')
    key = serialization.load_der_public_key(base64.b64decode(public_key))
    fields = dict(re.findall(r'name="([^"]+)"[^>]*value="([^"]*)"', page))
    fields.update(username=creds['username'], password=base64.b64encode(key.encrypt(creds['password'].encode(), padding.PKCS1v15())).decode())
    opener.open(urllib.request.Request(args.halo + '/login', data=urllib.parse.urlencode(fields).encode())).close()
    return creds['username']


def wait_for(check, description, seconds=45):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        result = check()
        if result:
            print('PASS:', description, flush=True)
            return result
        time.sleep(1)
    raise AssertionError('Timed out: ' + description)


API = '/apis/console.api.telegram-moment.halo.run/v1alpha1'
MOMENTS = '/apis/moment.halo.run/v1alpha1/moments'
sequence = int(time.time())
chat_id = -100123


def message(message_id, text='', photo=None, album=None):
    result = {'message_id': message_id, 'date': int(time.time()), 'chat': {'id': chat_id, 'type': 'channel'}}
    if photo:
        result['photo'] = [{'file_id': photo, 'file_unique_id': photo, 'width': 10, 'height': 10}]
        result['caption'] = text
    else:
        result['text'] = text
    if album:
        result['media_group_id'] = album
    return result


def send(value, edited=False):
    global sequence
    sequence += 1
    update = {'update_id': sequence, 'edited_channel_post' if edited else 'channel_post': value}
    request('/__test/updates', update, base=args.mock)
    return update


def moments():
    return request(MOMENTS)['items']


def find(message_id, text=None):
    for item in moments():
        if item['metadata'].get('deletionTimestamp'):
            continue
        if item['metadata'].get('annotations', {}).get('messageId') == str(message_id):
            if text is None or item['spec']['content']['raw'] == text:
                return item
    return None


owner = login()
request(API + '/stop', {}, 'POST')
path = '/api/v1alpha1/configmaps/telegram-moment-configmap'
try:
    config = request(path)
except urllib.error.HTTPError as error:
    if error.code != 404:
        raise
    config = {'apiVersion': 'v1alpha1', 'kind': 'ConfigMap', 'metadata': {'name': 'telegram-moment-configmap'}}
config['data'] = {group: json.dumps(values) for group, values in {
    'bot': {'botToken': '123:test', 'apiEndpoint': 'https://telegram-moment-mock', 'enableLogBuffer': True},
    'channel': {'channelEnabled': True, 'channelId': str(chat_id), 'channelReplyEnabled': False, 'channelFilter': 'skip'},
    'private': {'privateEnabled': False}, 'moment': {'momentOwner': owner},
    'storage': {'storagePolicy': 'default-policy', 'mediaDelaySeconds': 2},
}.items()}
request(path if 'version' in config['metadata'] else '/api/v1alpha1/configmaps', config,
        'PUT' if 'version' in config['metadata'] else 'POST')
status = request(API + '/restart', {}, 'POST')
assert status['phase'] == 'RUNNING', status
print('PASS: plugin startup', flush=True)
migrated = json.loads(request(path)['data']['bot'])
assert 'botToken' not in migrated and migrated['botTokenSecretName']
print('PASS: legacy token migrated to Secret reference', flush=True)

first_id = sequence
update = send(message(first_id, '集成测试：频道消息没有 from'))
first = wait_for(lambda: find(first_id), 'channel text without from')
name = first['metadata']['name']
assert first['spec']['approved'] and first['spec']['visible'] == 'PUBLIC'
public_client = urllib.request.build_opener(urllib.request.ProxyHandler({}))
with public_client.open(args.halo + '/apis/api.moment.halo.run/v1alpha1/moments/' + name) as response:
    assert json.load(response)['metadata']['name'] == name
print('PASS: published moment publicly visible', flush=True)
send(message(first_id, '原位编辑 <script>'), edited=True)
edited = wait_for(lambda: find(first_id, '原位编辑 <script>'), 'in-place edit')
assert edited['metadata']['name'] == name
assert '&lt;script&gt;' in edited['spec']['content']['html']
request('/__test/updates', update, base=args.mock)
assert len([item for item in moments() if item['metadata']['name'] == name]) == 1
print('PASS: duplicate update identity', flush=True)
redo = message(first_id + 10, '/redo')
redo['reply_to_message'] = message(first_id, '原位编辑 <script>')
send(redo)
time.sleep(2)
assert find(first_id)['metadata']['name'] == name
assert len([item for item in moments() if item['metadata']['name'] == name]) == 1
print('PASS: redo retains identity', flush=True)

album_id = first_id + 20
send(message(album_id, photo='photo-a-' + str(first_id), album=str(album_id)))
send(message(album_id + 1, '第二张说明', photo='photo-b-' + str(first_id), album=str(album_id)))
album = wait_for(lambda: find(album_id, '第二张说明'), 'album caption and attachments')
assert len(album['spec']['content']['medium']) == 2
for medium in album['spec']['content']['medium']:
    url = medium['url']
    if url.startswith('/'):
        url = args.halo + url
    with opener.open(url) as response:
        assert response.headers.get_content_type().startswith('image/')
        assert response.read().startswith(b'\x89PNG')
print('PASS: stored attachment bytes', flush=True)
send(message(album_id + 1, '成员编辑说明', photo='photo-b-' + str(first_id)), edited=True)
changed = wait_for(lambda: find(album_id, '成员编辑说明'), 'album member edit without group ID')
assert changed['metadata']['name'] == album['metadata']['name']
assert len(changed['spec']['content']['medium']) == 2

command = message(album_id + 2, '/rm')
command['reply_to_message'] = message(album_id + 1, '成员编辑说明')
send(command)
wait_for(lambda: not find(album_id), 'delete whole album via non-first member')

blocked_id = first_id + 40
send(message(blocked_id, '#skip 不应发布'))
time.sleep(4)
assert find(blocked_id) is None
print('PASS: filtered message', flush=True)

failed_id = first_id + 50
existing_tasks = {task['name'] for task in request(API + '/tasks')}
request('/__test/control', {'fail_files': True}, base=args.mock)
send(message(failed_id, '附件失败测试', photo='fail-' + str(first_id)))
failed_task = wait_for(lambda: next((task for task in request(API + '/tasks')
    if task['name'] not in existing_tasks and task['state'] == 'FAILED'), None), 'media failure retained after bounded retries', 50)
assert find(failed_id) is None
request('/__test/control', {'fail_files': False}, base=args.mock)
request(API + '/tasks/' + failed_task['name'] + '/retry', {}, 'POST')
wait_for(lambda: find(failed_id), 'media retry recovery')
assert 'sendMessage' not in request('/__test/state', base=args.mock)['calls']
print('PASS: replies disabled', flush=True)

pending_id = first_id + 60
send(message(pending_id, '重启恢复', photo='restart-' + str(first_id), album=str(pending_id)))
wait_for(lambda: request('/apis/telegram-moment.halo.run/v1alpha1/telegrambotcursors/bot-123')['spec']['nextOffset'] > sequence,
         'update persisted before restart')
request(API + '/stop', {}, 'POST')
assert request(API + '/status')['phase'] == 'STOPPED'
assert request(API + '/restart', {}, 'POST')['phase'] == 'RUNNING'
wait_for(lambda: find(pending_id), 'pending album survives bot restart')
anonymous = urllib.request.build_opener(urllib.request.ProxyHandler({}))
try:
    response = anonymous.open(args.halo + API + '/logs')
    assert response.headers.get_content_type() != 'application/json'
except urllib.error.HTTPError as error:
    assert error.code in (401, 403)
print('PASS: anonymous management access denied', flush=True)

# 使用真实 Halo 存储验证失效引用与只读状态接口，再恢复有效配置。
config = request(path)
saved_bot = json.loads(config['data']['bot'])
invalid_bot = dict(saved_bot, botTokenSecretName='missing-test-secret', botToken='123:must-not-fallback')
config['data']['bot'] = json.dumps(invalid_bot)
request(path, config, 'PUT')
status = request(API + '/restart', {}, 'POST')
assert status['phase'] == 'CONFIG_ERROR' and 'Secret' in status['error']
version = request(path)['metadata']['version']
request(API + '/status')
assert request(path)['metadata']['version'] == version
print('PASS: invalid Secret reference fails closed and status is read-only', flush=True)
config = request(path)
config['data']['bot'] = json.dumps(dict(saved_bot, apiEndpoint='http://telegram-moment-mock'))
request(path, config, 'PUT')
assert request(API + '/restart', {}, 'POST')['phase'] == 'CONFIG_ERROR'
print('PASS: production configuration rejects HTTP endpoint', flush=True)
config = request(path)
config['data']['bot'] = json.dumps(saved_bot)
request(path, config, 'PUT')
assert request(API + '/restart', {}, 'POST')['phase'] == 'RUNNING'
request(API + '/stop', {}, 'POST')
print('Integration checks completed', flush=True)
