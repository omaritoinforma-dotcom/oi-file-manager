<?php
declare(strict_types=1);
// OI Archivos bridge for shared PHP hosting. No access to the server filesystem is exposed.
ini_set('display_errors', '0');
header('Cache-Control: no-store');
header('X-Content-Type-Options: nosniff');
header('Referrer-Policy: no-referrer');
function reply($body, int $status=200): void {
    http_response_code($status); header('Content-Type: application/json; charset=utf-8');
    echo json_encode($body, JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES|JSON_THROW_ON_ERROR); exit;
}
function input(): array {
    if ((int)($_SERVER['CONTENT_LENGTH']??0)>200000) reply(['error'=>'Request too large'],413);
    $raw=file_get_contents('php://input',false,null,0,200001);
    if($raw===false||strlen($raw)>200000) reply(['error'=>'Request too large'],413);
    $p=json_decode($raw,true,32,JSON_THROW_ON_ERROR);
    if(!is_array($p)) reply(['error'=>'Invalid JSON'],400); return $p;
}
function auth(string $secret): bool {
    $actual=$_SERVER['HTTP_AUTHORIZATION']??$_SERVER['REDIRECT_HTTP_AUTHORIZATION']??'';
    if(!$actual && function_exists('getallheaders')) foreach(getallheaders() as $key=>$v) if(strtolower($key)==='authorization')$actual=$v;
    return strlen($secret)>=32 && hash_equals('Bearer '.$secret,$actual);
}
function rpc($id,$result): array { return ['jsonrpc'=>'2.0','id'=>$id,'result'=>$result]; }
function rpcError($id,int $code,string $message): array { return ['jsonrpc'=>'2.0','id'=>$id,'error'=>['code'=>$code,'message'=>$message]]; }
function content($value,bool $failed=false): array {
    return ['isError'=>$failed,'content'=>[['type'=>'text','text'=>is_string($value)?$value:json_encode($value,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES|JSON_THROW_ON_ERROR)]]];
}
// This path is outside every configured document root. Runtime files are owner-only.
$private=getenv('OI_BRIDGE_PRIVATE') ?: dirname(__DIR__,2).'/private/oi-android-bridge';
if(!is_dir($private) || !is_file($private.'/config.php')) reply(['error'=>'Bridge is not configured'],503);
$config=require $private.'/config.php';
$tools=json_decode(file_get_contents(__DIR__.'/tools.json'),true,32,JSON_THROW_ON_ERROR);
function state(callable $fn) {
    global $private;
    $old=umask(0077);$f=fopen($private.'/queue.lock','c+');umask($old);
    if(!$f||!flock($f,LOCK_EX))throw new RuntimeException('Queue unavailable');
    try{
        $raw=is_file($private.'/state.json')?file_get_contents($private.'/state.json'):'';$s=$raw?json_decode($raw,true,64,JSON_THROW_ON_ERROR):['lastSeen'=>0,'deviceName'=>'Sin emparejar','jobs'=>[]];
        $now=microtime(true);foreach($s['jobs'] as $id=>$j)if($j['expires']<$now)unset($s['jobs'][$id]);
        $out=$fn($s);$encoded=json_encode($s,JSON_THROW_ON_ERROR);
        $old=umask(0077);$tmp=$private.'/state-'.bin2hex(random_bytes(8)).'.tmp';
        $written=file_put_contents($tmp,$encoded);umask($old);
        if($written!==strlen($encoded)||!rename($tmp,$private.'/state.json'))throw new RuntimeException('Queue write failed');
        return $out;
    } finally {flock($f,LOCK_UN);fclose($f);}
}
function phoneStatus(): array {
    global $tools;
    return state(function(&$s)use($tools){return ['connected'=>microtime(true)-$s['lastSeen']<45,'deviceName'=>$s['deviceName'],'lastSeen'=>$s['lastSeen']?:null,'queued'=>count($s['jobs']),'capabilities'=>array_column($tools,'name')];});
}
function validArgs(string $name,$args): bool {
    global $tools;$spec=null;foreach($tools as $t)if($t['name']===$name)$spec=$t['inputSchema'];
    if(!$spec||!is_array($args))return false;
    foreach($args as $key=>$v){
        $f=$spec['properties'][$key]??null;if(!$f)return false;
        if($f['type']==='string'&&(!is_string($v)||strlen($v)>($key==='base64'?90000:65536)))return false;
        if($f['type']==='integer'&&(!is_int($v)||$v<($f['minimum']??0)||$v>($f['maximum']??PHP_INT_MAX)))return false;
        if(in_array($key,['path','destination'],true)&&(strlen($v)>4096||strpos($v,"\0")!==false))return false;
    }
    foreach($spec['required'] as $k)if(!array_key_exists($k,$args))return false;
    return true;
}
function callTool(string $name,array $args): array {
    if($name==='phone_status')return content(phoneStatus());
    if(!phoneStatus()['connected'])return content('OI Archivos está desconectado. Activa Conectar en los ajustes de la aplicación.',true);
    $id=bin2hex(random_bytes(16));$now=microtime(true);
    $added=state(function(&$s)use($id,$now,$name,$args){if(count($s['jobs'])>=32)return false;$s['jobs'][$id]=['id'=>$id,'action'=>$name,'args'=>(object)$args,'expires'=>$now+27,'state'=>'queued'];return true;});
    if(!$added)return content('Puente ocupado',true);
    ignore_user_abort(true);set_time_limit(30);
    try{
        while(microtime(true)-$now<26){
            $answer=state(function(&$s)use($id){return $s['jobs'][$id]['result']??null;});
            if($answer!==null)return content($answer,$answer['ok']!==true);
            usleep(250000);
        }
        return content('Tiempo de espera agotado. El resultado es desconocido; comprueba el archivo antes de repetir una escritura.',true);
    }finally{state(function(&$s)use($id){unset($s['jobs'][$id]);return null;});}
}
try {
    $path=parse_url($_SERVER['REQUEST_URI'],PHP_URL_PATH);
    $script=$_SERVER['SCRIPT_NAME'];
    if(strpos($script,'.php')!==false)$script=substr($script,0,strpos($script,'.php')+4);
    $base=rtrim(dirname($script),'/');
    if(strpos($path,$base)===0)$path=substr($path,strlen($base));
    if(strpos($path,'/index.php')===0)$path=substr($path,10);
    $path='/'.ltrim($path,'/');$method=$_SERVER['REQUEST_METHOD'];
    if($path==='/health'&&$method==='GET')reply(['ok'=>true,'service'=>'oi-android-bridge','version'=>'0.3.0']);
    if(strpos($path,'/device/')===0){
        if(!auth($config['device_token']))reply(['error'=>'Unauthorized'],401);
        if($path==='/device/next'&&$method==='GET'){
            $name=substr($_SERVER['HTTP_X_OI_DEVICE']??'OI Archivos',0,80);
            $job=state(function(&$s)use($name){$s['lastSeen']=microtime(true);$s['deviceName']=$name;foreach($s['jobs'] as &$j){if($j['state']==='queued'){$j['state']='claimed';return ['id'=>$j['id'],'action'=>$j['action'],'args'=>$j['args']];}}return ['id'=>null];});reply($job);
        }
        if($path==='/device/result'&&$method==='POST'){
            $p=input();if(!is_string($p['id']??null)||!is_bool($p['ok']??null))reply(['error'=>'Invalid result'],400);
            $done=state(function(&$s)use($p){$id=$p['id'];if(($s['jobs'][$id]['state']??null)!=='claimed')return false;$s['jobs'][$id]['state']='done';$s['jobs'][$id]['result']=['ok'=>$p['ok'],'data'=>$p['data']??null,'error'=>$p['error']??null];return true;});
            reply($done?['ok'=>true]:['error'=>'Unknown or expired command'],$done?200:404);
        }
        reply(['error'=>'Not found'],404);
    }
    if(strpos($path,'/api/v1/')===0){
        if(!auth($config['api_token']))reply(['error'=>'Unauthorized'],401);
        if($path==='/api/v1/status'&&$method==='GET')reply(phoneStatus());
        if($path==='/api/v1/tools'&&$method==='GET')reply(['tools'=>json_decode(file_get_contents(__DIR__.'/tools.json'))]);
        if($path==='/api/v1/run'&&$method==='POST'){$p=input();$name=$p['name']??'';$args=$p['args']??[];if(!is_string($name)||!validArgs($name,$args))reply(['error'=>'Invalid arguments'],400);$r=callTool($name,$args);reply($r,$r['isError']?503:200);}
        reply(['error'=>'Not found'],404);
    }
    if($path==='/mcp'){
        if(!auth($config['mcp_token'])){header('WWW-Authenticate: Bearer realm="OI Archivos"');reply(['error'=>'Unauthorized'],401);}
        if($method!=='POST'){header('Allow: POST');reply(['error'=>'POST required'],405);}
        $p=input();$id=$p['id']??null;$m=$p['method']??'';
        if(($p['jsonrpc']??null)!=='2.0'||!is_string($m))reply(rpcError($id,-32600,'Invalid request'),400);
        if(strpos($m,'notifications/')===0){http_response_code(202);exit;}
        if($m==='server/discover')reply(rpc($id,['supportedVersions'=>['2026-07-28','2025-03-26'],'capabilities'=>['tools'=>(object)[]]]));
        if($m==='initialize')reply(rpc($id,['protocolVersion'=>'2025-03-26','serverInfo'=>['name'=>'oi-android-bridge','version'=>'0.3.0'],'capabilities'=>['tools'=>(object)[]]]));
        if($m==='ping')reply(rpc($id,(object)[]));
        if($m==='tools/list')reply(rpc($id,['tools'=>json_decode(file_get_contents(__DIR__.'/tools.json'))]));
        if($m==='tools/call'){$name=$p['params']['name']??'';$args=$p['params']['arguments']??[];if(!is_string($name)||!validArgs($name,$args))reply(rpcError($id,-32602,'Invalid arguments'));reply(rpc($id,callTool($name,$args)));}
        reply(rpcError($id,-32601,'Method not found'));
    }
    if($path==='/'&&$method==='GET'){
        header('Content-Type: text/html; charset=utf-8');
        echo '<!doctype html><html lang="es"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>OI Archivos · Puente Android</title><style>body{font-family:system-ui;background:#121316;color:#eee;max-width:640px;margin:10vh auto;padding:24px}h1{color:#ffdc42}p{line-height:1.7}code{overflow-wrap:anywhere}</style><h1>OI Archivos · Puente Android</h1><p>Servidor instalado. La gestión de archivos requiere emparejar el teléfono y conectar el plugin privado.</p><p>En la APK abre Ajustes → Conector remoto de archivos, introduce el servidor HTTPS y tu token de dispositivo. Luego pulsa Guardar conexión y Conectar.</p><p>El acceso de escritura y la papelera se habilitan por separado en tu móvil.</p></html>';exit;
    }
    reply(['error'=>'Not found'],404);
}catch(Throwable $e){reply(['error'=>'Bridge request failed'],500);}
