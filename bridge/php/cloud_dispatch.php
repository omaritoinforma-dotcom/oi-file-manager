<?php
declare(strict_types=1);

/**
 * IONOS tools handled in the same MCP endpoint as the Android agent.
 * This file has no HTTP routes and is called only AFTER MCP/API authorization.
 * No SSH/SFTP password is requested: PHP uses the operator-selected host path.
 */
function oiCloudTools(): array {
    return ['cloud_status','cloud_list','cloud_stat','cloud_read_text',
        'cloud_mkdir','cloud_write_text','cloud_rename','cloud_delete'];
}
function oiCloudRoot(array $config): string {
    $given = $config['cloud_root'] ?? null;
    if (!is_string($given) || $given==='' || $given[0]!=='/') {
        throw new RuntimeException('IONOS sin configurar: falta cloud_root privado');
    }
    $root=realpath($given);
    $root= $root!==false ? rtrim($root,'/') : false;
    $private=realpath((string)($GLOBALS['private']??''));
    if (!$root || $root==='' || $root==='/' || !is_dir($root) ||
        ($private && ($root===$private || str_starts_with($private,$root.'/')
            || str_starts_with($root,$private.'/')))) {
        throw new RuntimeException('Raíz IONOS no válida');
    }
    return $root;
}
function oiCloudPath(string $path,string $root,bool $mayCreate=false): string {
    if (strlen($path)>2048 || !str_starts_with($path,'/') ||
        str_contains($path,"\0") || str_contains($path,'\\')) {
        throw new InvalidArgumentException('Ruta absoluta inválida');
    }
    if ($path==='/') return $root;
    $parts=explode('/',substr($path,1));
    if (count($parts)>32) throw new InvalidArgumentException('Ruta demasiado profunda');
    $cur=$root;
    foreach ($parts as $i=>$part) {
        if ($part==='' || $part==='.' || $part==='..' ||
            strlen($part)>255 || preg_match('/[\x00-\x1f]/',$part)) {
            throw new InvalidArgumentException('Ruta no permitida');
        }
        $next=$cur.'/'.$part;
        if (is_link($next))throw new RuntimeException('Enlace simbólico no permitido');
        if (file_exists($next)) {
            $resolved=realpath($next);
            if (!$resolved || ($resolved!==$root && !str_starts_with($resolved,$root.'/')))
                throw new RuntimeException('Fuera de IONOS');
            $cur=$resolved;
        } else {
            if (!$mayCreate || $i!==count($parts)-1 || !is_dir($cur))
                throw new RuntimeException('Archivo o directorio no encontrado');
            return $next;
        }
    }
    return $cur;
}
function oiCloudMeta(string $logical,string $file): array {
    $stat=@stat($file);
    if (!$stat)throw new RuntimeException('No se pueden leer metadatos');
    return ['name'=>basename($file),'path'=>$logical,'directory'=>is_dir($file),
        'size'=>is_file($file)?(int)$stat['size']:0,'modified'=>(int)$stat['mtime']];
}
function oiCloudExecute(string $name,array $args,array $config): array {
    if ($name==='cloud_status') {
        try {
            $root=oiCloudRoot($config);
            return ['configured'=>true,'available'=>is_readable($root),'location'=>'IONOS',
                'writeEnabled'=>($config['cloud_mcp_write_enabled']??false)===true,
                'phoneConnected'=>phoneStatus()['connected']];
        } catch (Throwable $e) {
            return ['configured'=>false,'available'=>false,'location'=>'IONOS',
                'writeEnabled'=>false,'error'=>$e->getMessage()];
        }
    }
    $root=oiCloudRoot($config);
    $path=(string)($args['path']??'/');
    $writing=in_array($name,['cloud_mkdir','cloud_write_text','cloud_rename','cloud_delete'],true);
    if ($writing && ($config['cloud_mcp_write_enabled']??false)!==true)
        throw new RuntimeException('Escritura cloud MCP desactivada en la configuración privada');
    if ($name==='cloud_list') {
        $dir=oiCloudPath($path,$root);
        if(!is_dir($dir))throw new InvalidArgumentException('No es una carpeta');
        $entries=[];
        foreach(scandir($dir) ?: [] as $item) {
            if($item==='.' || $item==='..' || is_link($dir.'/'.$item))continue;
            $itemPath=rtrim($path,'/').'/'.$item;
            $entries[]=oiCloudMeta($itemPath,oiCloudPath($itemPath,$root));
            if(count($entries)===1000)break;
        }
        usort($entries,static function(array $a,array $b) {
            return ($b['directory']<=>$a['directory'])?:strnatcasecmp($a['name'],$b['name']);
        });
        return ['location'=>'IONOS','path'=>$path,'items'=>$entries,'truncated'=>count($entries)>=1000];
    }
    if($name==='cloud_mkdir') {
        $file=oiCloudPath($path,$root,true);
        if(file_exists($file)||!@mkdir($file,0750))throw new RuntimeException('No se pudo crear la carpeta');
        return ['created'=>$path];
    }
    if($name==='cloud_write_text') {
        $file=oiCloudPath($path,$root,true);
        $data=$args['text']??null;
        if(!is_string($data)||strlen($data)>65536 || !preg_match('//u',$data))
            throw new InvalidArgumentException('Texto UTF-8 inválido o supera 64 KiB');
        if(file_exists($file))throw new RuntimeException('El destino ya existe');
        $fd=@fopen($file,'x');
        if(!$fd)throw new RuntimeException('No se pudo crear el archivo');
        try {
            if(fwrite($fd,$data)!==strlen($data)){@unlink($file);throw new RuntimeException('Escritura incompleta');}
        } finally {fclose($fd);}
        @chmod($file,0640);
        return ['created'=>$path,'bytes'=>strlen($data)];
    }
    $file=oiCloudPath($path,$root);
    if($name==='cloud_stat')return oiCloudMeta($path,$file);
    if($name==='cloud_read_text') {
        if(!is_file($file)||filesize($file)>65536)throw new RuntimeException('Solo texto hasta 64 KiB');
        $data=@file_get_contents($file);
        if(!is_string($data)||!preg_match('//u',$data))throw new RuntimeException('El archivo no es texto UTF-8');
        return ['path'=>$path,'text'=>$data];
    }
    if($file===$root)throw new InvalidArgumentException('No se puede modificar la raíz');
    if($name==='cloud_rename') {
        $dest=(string)($args['destination']??'');
        $target=oiCloudPath($dest,$root,true);
        if(file_exists($target)||!@rename($file,$target))throw new RuntimeException('No se pudo renombrar');
        return ['from'=>$path,'to'=>$dest];
    }
    if($name==='cloud_delete') {
        if(!is_file($file)||!@unlink($file))throw new RuntimeException('Solo se eliminan archivos regulares');
        return ['deleted'=>$path];
    }
    throw new InvalidArgumentException('Operación cloud desconocida');
}
