"""Integration test with a real PHP process and a simulated Android client."""
import concurrent.futures, contextlib, json, os, pathlib, socket, subprocess, tempfile, time, urllib.request, urllib.error
root=pathlib.Path(__file__).resolve().parent
with tempfile.TemporaryDirectory() as tmp:
    private=pathlib.Path(tmp); device='d'*64; mcp='m'*64; api='a'*64
    (private/'config.php').write_text("<?php return ['device_token'=>'%s','mcp_token'=>'%s','api_token'=>'%s'];"%(device,mcp,api))
    with socket.socket() as sock: sock.bind(('127.0.0.1',0)); port=sock.getsockname()[1]
    env={**os.environ,'OI_BRIDGE_PRIVATE':tmp,'PHP_CLI_SERVER_WORKERS':'4'}
    proc=subprocess.Popen(['php','-S',f'127.0.0.1:{port}','-t',str(root)],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL,start_new_session=True)
    base=f'http://127.0.0.1:{port}/index.php'
    def call(path,token=None,payload=None):
        headers={'Content-Type':'application/json'}
        if token: headers['Authorization']='Bearer '+token
        req=urllib.request.Request(base+path,data=None if payload is None else json.dumps(payload).encode(),headers=headers)
        try:
            with urllib.request.urlopen(req,timeout=30) as res: return res.status,json.load(res)
        except urllib.error.HTTPError as e: return e.code,json.load(e)
    def rpc(method,id=1,params=None): return call('/mcp',mcp,{'jsonrpc':'2.0','id':id,'method':method,'params':params or {}})
    try:
        for _ in range(100):
            try:
                if call('/health')[0]==200:break
            except OSError:pass
            time.sleep(.05)
        else:raise AssertionError('PHP did not start')
        assert call('/device/next')[0]==401
        assert call('/mcp',payload={})[0]==401
        assert call('/api/v1/status')[0]==401
        assert call('/api/v1/pairing',payload={})[0]==401
        pair=call('/api/v1/pairing',api,{})[1]
        ticket=pair['pairingUrl'].rsplit('/',1)[-1]
        assert len(ticket)==64
        assert call('/device/pair',payload={'ticket':'f'*64})[0]==410
        assert call('/device/pair',payload={'ticket':ticket})[1]['deviceToken']==device
        assert call('/device/pair',payload={'ticket':ticket})[0]==410
        assert rpc('initialize')[1]['result']['serverInfo']['name']=='oi-android-bridge'
        listed=rpc('tools/list')[1]['result']['tools'];assert len(listed)==25
        assert isinstance(listed[0]['inputSchema']['properties'],dict)
        assert rpc('tools/call',params={'name':'read_text','arguments':{'path':17}})[1]['error']['code']==-32602
        assert rpc('tools/call',params={'name':'debug_run','arguments':{'action':'su'}})[1]['error']['code']==-32602
        assert rpc('tools/call',params={'name':'list_roots'})[1]['result']['isError'] is True
        call('/device/next',device)
        with concurrent.futures.ThreadPoolExecutor() as pool:
            future=pool.submit(rpc,'tools/call',4,{'name':'list_roots','arguments':{}})
            cmd={}
            for _ in range(50):
                cmd=call('/device/next',device)[1]
                if cmd.get('id'):break
                time.sleep(.05)
            assert cmd['action']=='list_roots',cmd
            assert call('/device/next',device)[1]['id'] is None
            result={'id':cmd['id'],'ok':True,'data':{'roots':[{'path':'/storage/emulated/0'}]}}
            assert call('/device/result',device,result)[0]==200
            assert call('/device/result',device,result)[0]==404
            answer=future.result()[1]['result']; assert answer['isError'] is False,answer
            assert '/storage/emulated/0' in answer['content'][0]['text']
        assert not json.loads((private/'state.json').read_text())['jobs']
        assert call('/api/v1/status',api)[1]['connected'] is True
        assert call('/api/v1/status',device)[0]==401
        print('PASS: PHP MCP/REST authentication, schema, offline, atomic queue, single delivery, duplicate rejection and cleanup')
    finally:
        import signal
        os.killpg(proc.pid,signal.SIGTERM);proc.wait(timeout=5)
