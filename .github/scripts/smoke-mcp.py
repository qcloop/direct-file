#!/usr/bin/env python3
"""Boot the production image with ephemeral Redis; verify HTTP and MCP discovery."""
import json
import subprocess
import sys
import time
import urllib.request
import uuid

prefix = 'ci-smoke-' + uuid.uuid4().hex[:12]
network, redis, calculator = prefix, prefix + '-redis', prefix + '-calculator'


def run(*args):
    return subprocess.check_output(args, text=True).strip()


try:
    run('docker', 'network', 'create', network)
    run('docker', 'run', '-d', '--name', redis, '--network', network,
        '--network-alias', 'redis', 'redis:7.4-alpine')
    run('docker', 'run', '-d', '--name', calculator, '--network', network,
        '--cap-drop', 'ALL', '--memory', '1g', '-p', '127.0.0.1:18090:8090',
        '-e', 'REDIS_URL=redis://redis:6379', sys.argv[1], '--tax-advisor.allow-draft-artifacts=true')
    for _ in range(90):
        try:
            with urllib.request.urlopen('http://127.0.0.1:18090/actuator/health/readiness', timeout=3) as response:
                assert json.load(response)['status'] == 'UP'
            break
        except Exception:
            time.sleep(2)
    else:
        raise RuntimeError('Calculator readiness did not pass')
    headers = {'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream'}
    def rpc(payload):
        request = urllib.request.Request('http://127.0.0.1:18090/mcp', data=json.dumps(payload).encode(), headers=headers)
        with urllib.request.urlopen(request, timeout=15) as response:
            if response.headers.get('Mcp-Session-Id'):
                headers['Mcp-Session-Id'] = response.headers['Mcp-Session-Id']
            body = response.read().decode()
        if not body: return None
        if body.lstrip().startswith('{'): return json.loads(body)
        data = '\n'.join(line[5:].strip() for line in body.splitlines() if line.startswith('data:'))
        return json.loads(data)
    response = rpc({'jsonrpc': '2.0', 'id': 1, 'method': 'initialize', 'params': {
        'protocolVersion': '2025-03-26', 'capabilities': {}, 'clientInfo': {'name': 'ci-smoke', 'version': '1'}}})
    assert response.get('result') and headers.get('Mcp-Session-Id'), response
    rpc({'jsonrpc': '2.0', 'method': 'notifications/initialized'})
    response = rpc({'jsonrpc': '2.0', 'id': 2, 'method': 'tools/list', 'params': {}})
    tools = {tool['name'] for tool in response['result']['tools']}
    assert {'create_session', 'calculate_se_tax'}.issubset(tools), tools
    print('Production image readiness and MCP initialization/discovery passed.')
finally:
    subprocess.run(['docker', 'rm', '-f', calculator, redis], check=False)
    subprocess.run(['docker', 'network', 'rm', network], check=False)
