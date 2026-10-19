"use strict";
const el=id=>document.getElementById(id);
let csrf="",path="/",busy=false;
const api=async(action,opts={})=>{
  const r=await fetch("index.php?action="+encodeURIComponent(action),{credentials:"same-origin",cache:"no-store",...opts});
  let data={};try{data=await r.json()}catch{}
  if(!r.ok)throw Error(data.error||"Error HTTP "+r.status);
  return data;
};
const message=s=>{el("error").textContent=s||"";};
const show=logged=>{
  el("login").classList.toggle("hidden",logged);
  el("app").classList.toggle("hidden",!logged);
  el("logout").classList.toggle("hidden",!logged);
};
const action=async(name,body)=>{
  const r=await api(name,{method:"POST",headers:{"Content-Type":"application/json","X-CSRF-Token":csrf},body:JSON.stringify(body)});
  await refresh();return r;
};
const fmt=n=>!n?"0 B":n<1024?n+" B":n<1024*1024?(n/1024).toFixed(1)+" KB":(n/1048576).toFixed(1)+" MB";
function button(text,fn) {
  const b=document.createElement("button");b.type="button";b.textContent=text;b.addEventListener("click",e=>{e.stopPropagation();fn()});return b;
}
async function refresh(){
  if(busy)return;busy=true;message("");el("status").textContent="Sincronizando…";
  try{
    const d=await api("list&path="+encodeURIComponent(path));
    el("path").textContent=path;el("items").replaceChildren();
    const entries=d.items||[];if(!entries.length){const p=document.createElement("p");p.className="small";p.style.padding="18px";p.textContent="Carpeta vacía";el("items").append(p)}
    for(const item of entries){
      const row=document.createElement("div");row.className="file";
      const ico=document.createElement("span");ico.className="icon";ico.textContent=item.directory?"📁":"📄";
      const body=document.createElement("div");body.className="fileBody";
      const name=document.createElement("span");name.className="name";name.textContent=item.name;
      const details=document.createElement("span");details.className="details";details.textContent=item.directory?"Carpeta":fmt(item.size);
      body.append(name,details);const actions=document.createElement("div");actions.className="fileActions";
      if(item.directory){row.style.cursor="pointer";row.tabIndex=0;row.addEventListener("click",()=>{path=item.path;refresh()});
        row.addEventListener("keydown",e=>{if(e.key==="Enter"){path=item.path;refresh()}})}
      else actions.append(button("↓",()=>{location.href="index.php?action=download&path="+encodeURIComponent(item.path)}));
      actions.append(button("✎",async()=>{const name=prompt("Nuevo nombre",item.name);if(name&&name!==item.name)try{await action("rename",{path:item.path,destination:(path==="/"?"/":path+"/")+name})}catch(e){message(e.message)}}));
      if(!item.directory)actions.append(button("×",async()=>{if(confirm("¿Borrar definitivamente "+item.name+"?"))try{await action("delete",{path:item.path})}catch(e){message(e.message)}}));
      row.append(ico,body,actions);el("items").append(row);
    }
    el("status").textContent=(entries.length)+" elementos"+(d.truncated?" (lista limitada a 1000)":"");
  }catch(e){message(e.message);el("status").textContent="Sin conexión";}finally{busy=false}
}
el("loginForm").addEventListener("submit",async e=>{
  e.preventDefault();message("");
  try{const r=await api("login",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({password:el("pass").value})});
    csrf=r.csrf;el("pass").value="";show(true);refresh()}catch(err){message(err.message)}
});
el("logout").addEventListener("click",async()=>{try{await api("logout",{method:"POST",headers:{"X-CSRF-Token":csrf}})}catch{}csrf="";show(false)});
el("back").addEventListener("click",()=>{const p=path.split("/").filter(Boolean);p.pop();path="/"+p.join("/");refresh()});
el("refresh").addEventListener("click",refresh);
el("mkdir").addEventListener("click",async()=>{const name=prompt("Nombre de la carpeta");if(name)try{await action("mkdir",{path:(path==="/"?"/":path+"/")+name})}catch(e){message(e.message)}});
el("upload").addEventListener("change",async e=>{
  const f=e.target.files[0];if(!f)return;const data=new FormData();data.append("path",path);data.append("file",f);
  try{await api("upload",{method:"POST",headers:{"X-CSRF-Token":csrf},body:data});await refresh()}catch(err){message(err.message)}finally{e.target.value=""}
});
api("session").then(s=>{if(s.authenticated){csrf=s.csrf;show(true);refresh()}}).catch(()=>{});
