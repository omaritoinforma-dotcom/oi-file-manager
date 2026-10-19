#!/usr/bin/env node
"use strict";

// OI Archivos bridge: one explicitly paired phone, outbound HTTPS polling,
// and a stateless Streamable HTTP MCP endpoint for ChatGPT/other MCP clients.
// Secrets belong in environment variables, never in the repository.
const http = require("node:http");
const crypto = require("node:crypto");

const PORT = Number(process.env.PORT || 8787);
const DEVICE_TOKEN = process.env.OI_DEVICE_TOKEN || "";
const MCP_ROUTE_KEY = process.env.OI_MCP_ROUTE_KEY || "";
const PUBLIC_ORIGIN = process.env.OI_PUBLIC_ORIGIN || "";
if (!DEVICE_TOKEN || DEVICE_TOKEN.length < 32 || !MCP_ROUTE_KEY || MCP_ROUTE_KEY.length < 32) {
  console.error("Set OI_DEVICE_TOKEN and OI_MCP_ROUTE_KEY to independent random secrets (32+ chars).");
  process.exit(1);
}
const TOOL_DEF = [
  ["phone_status", "Check Android connection status and available tools.", {}],
  ["list_roots", "List Android storage volumes currently available to OI Archivos.", {}],
  ["list_files", "List a folder in Android shared storage. Empty path opens primary storage.",
    {path:{type:"string",description:"Absolute Android folder path, e.g. /storage/emulated/0/Download"}}],
  ["file_info", "Get metadata for a file or directory.",
    {path:{type:"string",description:"Absolute path"}}],
  ["read_text", "Read UTF-8 text from an accessible file, up to 64 KiB.",
    {path:{type:"string"}}],
  ["make_directory", "Create a new directory. Requires owner-enabled file changes.",
    {path:{type:"string"}}],
  ["write_new_text", "Create a new UTF-8 file, never overwrite an existing file, max 64 KiB. Requires owner-enabled changes.",
    {path:{type:"string"},content:{type:"string"}}],
  ["rename_file", "Rename an existing file or directory, failing when the destination already exists. Requires owner-enabled changes.",
    {path:{type:"string"},destination:{type:"string"}}],
  ["copy_file", "Copy an accessible file or directory without overwriting. Requires owner-enabled changes.",
    {path:{type:"string"},destination:{type:"string"}}],
  ["move_file", "Move an accessible file or directory without overwriting. Requires owner-enabled changes.",
    {path:{type:"string"},destination:{type:"string"}}],
  ["trash_file", "Move a file to the application's recoverable trash, not permanent delete. Requires separately enabled trash permission.",
    {path:{type:"string"}}]
];
const tools = TOOL_DEF.map(([name,description,properties])=>({
  name,description,inputSchema:{type:"object",properties,
    required:Object.entries(properties).filter(([k])=>!(k==="path" && name==="list_files")).map(([k])=>k),
    additionalProperties:false}
}));
const jobs = [];
const inFlight = new Map();
let lastSeen = 0, lastResult = null, deviceName = "OI Archivos";
const MAX_WAIT_MS = 29000;
const json = (res,status,data) => {
  const text=JSON.stringify(data);
  res.writeHead(status,{"Content-Type":"application/json; charset=utf-8",
    "Cache-Control":"no-store","X-Content-Type-Options":"nosniff"});
  res.end(text);
};
const sameSecret=(a,b)=>{
  const aa=Buffer.from(String(a)), bb=Buffer.from(String(b));
  return aa.length===bb.length && crypto.timingSafeEqual(aa,bb);
};
function readBody(req) {
  return new Promise((resolve,reject)=>{
    let total=0,parts=[];
    req.on("data",c=>{ total+=c.length; if(total>200000) {reject(Error("Request too large"));req.destroy();} else parts.push(c);});
    req.on("end",()=>{try{resolve(JSON.parse(Buffer.concat(parts).toString("utf8")));}catch(e){reject(e);}});
    req.on("error",reject);
  });
}
function deviceAuthorized(req) {
  return sameSecret(req.headers.authorization || "", "Bearer " + DEVICE_TOKEN);
}
function enqueue(action,args) {
  return new Promise((resolve,reject)=>{
    const id=crypto.randomUUID();
    const timeout=setTimeout(()=>{inFlight.delete(id);reject(Error("Phone unavailable or command timed out"));},MAX_WAIT_MS);
    inFlight.set(id,{resolve,reject,timeout});
    jobs.push({id,action,args});
  });
}
function rpcResponse(id,result){return {jsonrpc:"2.0",id,result};}
function rpcError(id,code,message){return {jsonrpc:"2.0",id,error:{code,message}};}
async function mcp(payload) {
  const {method,id,params={}}=payload||{};
  if (method==="initialize") return rpcResponse(id,{protocolVersion:"2025-03-26",
    capabilities:{tools:{listChanged:false}},serverInfo:{name:"oi-android",version:"0.1.0"}});
  if (method==="notifications/initialized") return null;
  if (method==="ping") return rpcResponse(id,{});
  if (method==="tools/list") return rpcResponse(id,{tools});
  if (method==="tools/call") {
    const name=params?.name, args=params?.arguments || {};
    if (!tools.some(x=>x.name===name)) return rpcError(id,-32602,"Unknown tool");
    if (name==="phone_status") return rpcResponse(id,{content:[{type:"text",text:JSON.stringify({
      connected:Date.now()-lastSeen<45000, deviceName,
      queued:jobs.length, capabilities:TOOL_DEF.map(x=>x[0])
    })}]});
    if(Date.now()-lastSeen>45000) return rpcResponse(id,{isError:true,content:[{type:"text",text:"OI Archivos is offline."}]});
    try{
      const result=await enqueue(name,args);
      return rpcResponse(id,{isError:result.ok===false,content:[{type:"text",
        text:JSON.stringify(result)}]});
    }catch(e){
      return rpcResponse(id,{isError:true,content:[{type:"text",text:e.message}]});
    }
  }
  return rpcError(id??null,-32601,"Method not found");
}
const server=http.createServer(async (req,res)=>{
  const u=new URL(req.url,"http://localhost");
  // No browser CORS, no public file serving.
  if(req.method==="GET" && u.pathname==="/health")
    return json(res,200,{ok:true,service:"oi-android-bridge"});
  if(u.pathname.startsWith("/device/")){
    if (!deviceAuthorized(req)) return json(res,401,{error:"Unauthorized"});
    lastSeen=Date.now();
    deviceName=String(req.headers["x-oi-device"]||"OI Archivos").slice(0,80);
    if(req.method==="GET" && u.pathname==="/device/next"){
      // Short polling. No background connection to the phone is exposed.
      return json(res,200,jobs.shift()||{id:null});
    }
    if(req.method==="POST" && u.pathname==="/device/result"){
      const body=await readBody(req);
      const target=inFlight.get(body.id);
      if (!target) return json(res,404,{error:"Unknown or expired job"});
      clearTimeout(target.timeout);inFlight.delete(body.id);
      target.resolve({ok:body.ok===true,data:body.data??null,error:body.error??null});
      lastResult=Date.now();
      return json(res,200,{ok:true});
    }
    return json(res,404,{error:"Unknown device endpoint"});
  }
  if(u.pathname==="/mcp/"+MCP_ROUTE_KEY){
    if(req.method!=="POST") return json(res,405,{error:"POST required"});
    let body;
    try{body=await readBody(req);}catch(e){return json(res,400,{error:"Malformed request"});}
    const result=await mcp(body);
    if(result===null){res.writeHead(202);return res.end();}
    res.setHeader("Mcp-Session-Id","oi-android-stateless");
    return json(res,200,result);
  }
  return json(res,404,{error:"Not found"});
});
server.requestTimeout=35000;
server.listen(PORT,()=>console.log("OI Android bridge listening on "+PORT+
  (PUBLIC_ORIGIN?" | MCP: "+PUBLIC_ORIGIN.replace(/\/$/,"")+"/mcp/<private-key>":"")));
