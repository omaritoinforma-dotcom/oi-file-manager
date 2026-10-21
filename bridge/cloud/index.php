<?php
declare(strict_types=1);
/**
 * OI Archivos Cloud: one IONOS filesystem root, shared by browser, Android and MCP.
 * PHP runs beside the SFTP-accessible files; no SFTP passwords in the APK.
 */
ini_set('display_errors','0');
header('X-Content-Type-Options: nosniff');
header('Referrer-Policy: no-referrer');
header('Cache-Control: no-store');
header("Content-Security-Policy: default-src 'none'; frame-ancestors 'none'");
$private=getenv('OI_BRIDGE_PRIVATE') ?: dirname(__DIR__,2).'/private/oi-android-bridge';
if (!is_file($private.'/config.php')) { http_response_code(503); exit('Not configured'); }
$cfg=require $private.'/config.php';
// Mount selection is non-secret and separate from bridge credentials.
$mountFile=$private.'/cloud-root.php';
if (is_file($mountFile)) {
    $mount=require $mountFile;
    if (is_array($mount)&&isset($mount['cloud_root'])&&is_string($mount['cloud_root']))
        $cfg['cloud_root']=$mount['cloud_root'];
}
$root=isset($cfg['cloud_root'])&&is_string($cfg['cloud_root'])?realpath($cfg['cloud_root']):false;
if (!$root||!is_dir($root)) {
    http_response_code(503); exit('Cloud root not configured');
}
if (!isset($cfg['device_token'],$cfg['mcp_token'])) {
    http_response_code(503); exit('Cloud bridge credentials not configured');
}
$root=rtrim($root,DIRECTORY_SEPARATOR);
if ($root===''||$root==='/'||strpos($root,realpath($private)?:$private)===0) { http_response_code(503);exit('Unsafe root'); }
if (PHP_SAPI!=='cli' && (empty($_SERVER['HTTPS'])||$_SERVER['HTTPS']==='off')
    && ((getenv('OI_CLOUD_TRUST_PROXY')!=='1')||($_SERVER['HTTP_X_FORWARDED_PROTO']??'')!=='https')) {
    http_response_code(403); exit('HTTPS required');
}
session_name('OI_CLOUD');
session_set_cookie_params(['lifetime'=>0,'path'=>'/','secure'=>true,'httponly'=>true,'samesite'=>'Strict']);
session_start();
function respond($data,int $status=200): void {
    http_response_code($status);header('Content-Type: application/json; charset=utf-8');
    echo json_encode($data,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES|JSON_THROW_ON_ERROR);exit;
}
function bearer(string $key): bool {
    $h=$_SERVER['HTTP_AUTHORIZATION']??$_SERVER['REDIRECT_HTTP_AUTHORIZATION']??'';
    if (!$h && function_exists('getallheaders')) foreach(getallheaders() as $k=>$v)
        if (strtolower($k)==='authorization') $h=$v;
    return strlen($key)>=32 && hash_equals('Bearer '.$key,$h);
}
function sessionAuth(): bool {return !empty($_SESSION['cloud_authenticated']);}
function authenticated(): bool {
    global $cfg;
    return sessionAuth()||bearer((string)$cfg['device_token']);
}
function csrf(): void {
    if (sessionAuth()&&!bearer((string)($GLOBALS['cfg']['device_token']??''))) {
        if (!hash_equals((string)($_SESSION['cloud_csrf']??''),(string)($_SERVER['HTTP_X_CSRF_TOKEN']??'')))
            respond(['error'=>'CSRF token required'],403);
    }
}
function input(): array {
    $length=(int)($_SERVER['CONTENT_LENGTH']??0);
    if ($length>131072)respond(['error'=>'Request too large'],413);
    $raw=file_get_contents('php://input',false,null,0,131073);
    if ($raw===false||strlen($raw)>131072)respond(['error'=>'Request too large'],413);
    $data=json_decode($raw,true);
    if (!is_array($data))respond(['error'=>'Invalid JSON'],400);
    return $data;
}
function segments(string $path): array {
    if (strlen($path)>2048||strpos($path,"\0")!==false||strpos($path,'\\')!==false)respond(['error'=>'Invalid path'],400);
    $parts=explode('/',trim($path,'/'));
    if ($parts===[''])return [];
    if (count($parts)>32)respond(['error'=>'Path too deep'],400);
    foreach($parts as $p) if ($p===''||$p==='.'||$p==='..'||strlen($p)>255||preg_match('/[\x00-\x1f]/',$p))
        respond(['error'=>'Invalid path segment'],400);
    return $parts;
}
function servicePrivatePath(string $candidate): bool {
    global $root,$private;
    $blocked=[$root.'/private',$root.'/phonebridge-private',$root.'/.ssh',$root.'/.oi_private_key.php',$private];
    foreach($blocked as $path) if ($candidate===$path||strpos($candidate,$path.DIRECTORY_SEPARATOR)===0) return true;
    return false;
}
function located(string $path,bool $creating=false): string {
    global $root;
    $parts=segments($path);$cur=$root;
    foreach ($parts as $i=>$p) {
        $candidate=$cur.DIRECTORY_SEPARATOR.$p;
        if (servicePrivatePath($candidate)) respond(['error'=>'Service-private path'],403);
        if (is_link($candidate)) respond(['error'=>'Symlink not allowed'],403);
        if (file_exists($candidate)) {
            $canonical=realpath($candidate);
            if (!$canonical||($canonical!==$root&&strpos($canonical,$root.DIRECTORY_SEPARATOR)!==0))
                respond(['error'=>'Path out of root'],403);
            $cur=$canonical;
        } else {
            if (!$creating||$i!==count($parts)-1||!is_dir($cur))
                respond(['error'=>'Path does not exist'],404);
            return $candidate;
        }
    }
    return $cur;
}
function entry(string $path,string $filename): array {
    $actual=located(($path==='/'?'':$path).'/'.$filename);
    $stat=stat($actual);
    return ['name'=>$filename,'path'=>rtrim($path,'/').'/'.$filename,
        'directory'=>is_dir($actual),'size'=>is_file($actual)?(int)$stat['size']:0,
        'modified'=>(int)$stat['mtime']];
}
function cloudList(string $path): array {
    $dir=located($path);if(!is_dir($dir))respond(['error'=>'Not a directory'],400);
    $items=[];
    foreach(scandir($dir)?:[] as $name) {
        if($name==='.'||$name==='..'||is_link($dir.DIRECTORY_SEPARATOR.$name)||servicePrivatePath($dir.DIRECTORY_SEPARATOR.$name))continue;
        $items[]=entry($path,$name);
        if(count($items)>=1000)break;
    }
    usort($items,static function($a,$b){return ($b['directory']<=>$a['directory'])?:strnatcasecmp($a['name'],$b['name']);});
    return ['path'=>$path,'items'=>$items,'truncated'=>count($items)===1000,'online'=>true];
}
function operation(string $action,array $args) {
    $path=(string)($args['path']??'/');
    switch($action) {
        case 'status': return ['ok'=>true,'service'=>'oi-cloud','version'=>'0.4.0','online'=>true];
        case 'list': return cloudList($path);
        case 'stat':
            $f=located($path);$s=stat($f);
            return ['path'=>$path,'directory'=>is_dir($f),'size'=>is_file($f)?$s['size']:0,'modified'=>$s['mtime']];
        case 'read_text':
            $f=located($path);if(!is_file($f)||filesize($f)>65536)respond(['error'=>'Only text files up to 64 KiB'],413);
            $text=file_get_contents($f);if($text===false||preg_match('//u',$text)!==1)respond(['error'=>'Not UTF-8 text'],415);
            return ['path'=>$path,'text'=>$text];
        case 'mkdir':
            $f=located($path,true);if(file_exists($f)||!mkdir($f,0750))respond(['error'=>'Cannot create directory'],409);
            return ['created'=>$path];
        case 'rename':
            $to=(string)($args['destination']??'');$src=located($path);$dst=located($to,true);
            if ($src===$GLOBALS['root']||file_exists($dst)||!rename($src,$dst))respond(['error'=>'Cannot rename'],409);
            return ['from'=>$path,'to'=>$to];
        case 'delete':
            $f=located($path);
            if($f===$GLOBALS['root']||!is_file($f)||!unlink($f))respond(['error'=>'Only regular files may be deleted'],409);
            return ['deleted'=>$path];
        case 'write_text':
            $f=located($path,true);$value=$args['text']??null;
            if (!is_string($value)||strlen($value)>65536||file_exists($f))respond(['error'=>'New text file only; max 64 KiB'],400);
            $fh=@fopen($f,'x');if(!$fh)respond(['error'=>'File exists or is not writable'],409);
            try {if(fwrite($fh,$value)!==strlen($value)){fclose($fh);@unlink($f);respond(['error'=>'Write failed'],500);}}finally{if(is_resource($fh))fclose($fh);}
            return ['created'=>$path,'bytes'=>strlen($value)];
    }
    respond(['error'=>'Unknown action'],400);
}
$action=(string)($_GET['action']??'');
$method=$_SERVER['REQUEST_METHOD']??'GET';
try {
    if($action==='login'&&$method==='POST') {
        if (!isset($cfg['cloud_web_password_hash'])) respond(['error'=>'Web login is not configured'],503);
        $p=input();$password=(string)($p['password']??'');
        // Shared throttle prevents unlimited guesses from fresh browser sessions.
        $ip=(string)($_SERVER['REMOTE_ADDR']??'unknown');
        $rate=$private.'/cloud-login-'.hash('sha256',$ip).'.json';
        $s=is_file($rate)?json_decode((string)file_get_contents($rate),true):null;
        if(is_array($s)&&($s['until']??0)>time())respond(['error'=>'Try again later'],429);
        if(!password_verify($password,(string)$cfg['cloud_web_password_hash'])){
            $n=(is_array($s)&&($s['time']??0)>time()-900)?($s['n']??0)+1:1;
            file_put_contents($rate,json_encode(['n'=>$n,'time'=>time(),'until'=>$n>=5?time()+900:0]),LOCK_EX);
            usleep(350000);respond(['error'=>'Incorrect credentials'],401);
        }
        @unlink($rate);session_regenerate_id(true);
        $_SESSION['cloud_authenticated']=true;$_SESSION['cloud_csrf']=bin2hex(random_bytes(24));
        respond(['ok'=>true,'csrf'=>$_SESSION['cloud_csrf']]);
    }
    if($action==='logout'&&$method==='POST') {
        if(!sessionAuth())respond(['error'=>'Unauthorized'],401);csrf();session_destroy();respond(['ok'=>true]);
    }
    if($action==='session'&&$method==='GET')respond(['authenticated'=>sessionAuth(),'csrf'=>sessionAuth()?$_SESSION['cloud_csrf']:null]);
    if($action==='mcp'&&$method==='POST') {
        if(!bearer((string)$cfg['mcp_token']))respond(['error'=>'Unauthorized'],401);
        $p=input();$id=$p['id']??null;$name=(string)($p['method']??'');
        if($name==='initialize'||$name==='server/discover')
            respond(['jsonrpc'=>'2.0','id'=>$id,'result'=>['protocolVersion'=>'2025-03-26','serverInfo'=>['name'=>'oi-cloud','version'=>'0.4.0'],'capabilities'=>['tools'=>(object)[]]]]);
        if($name==='ping')respond(['jsonrpc'=>'2.0','id'=>$id,'result'=>(object)[]]);
        $tools=['list','stat','read_text','mkdir','rename','delete','write_text'];
        if($name==='tools/list'){
            $r=[];foreach($tools as $t){
                $props=['path'=>['type'=>'string']];
                if($t==='rename')$props['destination']=['type'=>'string'];
                if($t==='write_text')$props['text']=['type'=>'string'];
                $r[]=['name'=>'cloud_'.$t,'description'=>'IONOS cloud '.$t,'inputSchema'=>['type'=>'object','properties'=>$props,'required'=>array_keys($props),'additionalProperties'=>false]];
            }respond(['jsonrpc'=>'2.0','id'=>$id,'result'=>['tools'=>$r]]);
        }
        if($name==='tools/call'){
            $tool=(string)($p['params']['name']??'');$args=$p['params']['arguments']??[];
            $t=substr($tool,6);
            if(strpos($tool,'cloud_')!==0||!in_array($t,$tools,true)||!is_array($args))
                respond(['jsonrpc'=>'2.0','id'=>$id,'error'=>['code'=>-32602,'message'=>'Invalid arguments']]);
            foreach(($t==='rename'?['path','destination']:($t==='write_text'?['path','text']:['path'])) as $f)
                if(!isset($args[$f])||!is_string($args[$f]))respond(['jsonrpc'=>'2.0','id'=>$id,'error'=>['code'=>-32602,'message'=>'Missing argument']]);
            $r=operation($t,$args);respond(['jsonrpc'=>'2.0','id'=>$id,'result'=>['content'=>[['type'=>'text','text'=>json_encode($r,JSON_UNESCAPED_UNICODE|JSON_UNESCAPED_SLASHES)]]]]);
        }
        respond(['jsonrpc'=>'2.0','id'=>$id,'error'=>['code'=>-32601,'message'=>'Unknown method']]);
    }
    if(!authenticated())respond(['error'=>'Unauthorized'],401);
    if($action==='download'&&$method==='GET') {
        $f=located((string)($_GET['path']??''));if(!is_file($f))respond(['error'=>'Not a file'],400);
        header('Content-Type: application/octet-stream');
        header('Content-Disposition: attachment; filename="'.str_replace(['"',"\\r","\\n"],'_',basename($f)).'"');
        header('Content-Length: '.filesize($f));readfile($f);exit;
    }
    if($action==='upload'&&$method==='POST'){
        csrf();
        $path=(string)($_POST['path']??'/');$dir=located($path);
        if(!is_dir($dir)||!isset($_FILES['file'])||is_array($_FILES['file']['name']))respond(['error'=>'Invalid upload'],400);
        $name=(string)$_FILES['file']['name'];$target=located(rtrim($path,'/').'/'.$name,true);
        if(file_exists($target)||$_FILES['file']['error']!==UPLOAD_ERR_OK||!is_uploaded_file($_FILES['file']['tmp_name']))
            respond(['error'=>'Upload failed or file exists'],409);
        if((int)$_FILES['file']['size']>33554432)respond(['error'=>'32 MiB upload limit'],413);
        if(!move_uploaded_file($_FILES['file']['tmp_name'],$target))respond(['error'=>'Upload failed'],500);
        chmod($target,0640);respond(['uploaded'=>rtrim($path,'/').'/'.$name]);
    }
    if($action==='list'&&$method==='GET')respond(operation('list',['path'=>(string)($_GET['path']??'/')]));
    if($action==='status'&&$method==='GET')respond(operation('status',[]));
    if(in_array($action,['mkdir','rename','delete','write_text'],true)&&$method==='POST'){
        csrf();respond(operation($action,input()));
    }
    respond(['error'=>'Not found'],404);
} catch(Throwable $e) {
    error_log('OI Cloud: '.$e->getMessage());
    respond(['error'=>'Operation failed'],500);
}
