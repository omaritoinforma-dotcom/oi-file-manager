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
const API_TOKEN = process.env.OI_API_TOKEN || "";
if (!DEVICE_TOKEN || DEVICE_TOKEN.length < 32 || !MCP_ROUTE_KEY || MCP_ROUTE_KEY.length < 32 || (API_TOKEN && API_TOKEN.length < 32)) {
  console.error("Set OI_DEVICE_TOKEN and OI_MCP_ROUTE_KEY to independent random secrets (32+ chars).");
  process.exit(1);
}
const tools = require("./tools.json");
const TOOL_DEF = tools.map(t=>[t.name,t.description,t.inputSchema.properties]);
const jobs = [];
const inFlight = new Map();
let lastSeen = 0, lastResult = null, deviceName = "OI Archivos";
const MAX_WAIT_MS = 29000;
const MAX_PENDING = 32;
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
  if (inFlight.size >= MAX_PENDING) return Promise.reject(Error("Bridge busy"));
  return new Promise((resolve,reject)=>{
    const id=crypto.randomUUID();
    const timeout=setTimeout(()=>{inFlight.delete(id);const i=jobs.findIndex(j=>j.id===id);if(i>=0)jobs.splice(i,1);reject(Error("Phone unavailable or command timed out"));},MAX_WAIT_MS);
    inFlight.set(id,{resolve,reject,timeout});
    jobs.push({id,action,args});
  });
}
function rpcResponse(id,result){return {jsonrpc:"2.0",id,result};}
function rpcError(id,code,message){return {jsonrpc:"2.0",id,error:{code,message}};}
// Fail closed on unexpected or oversized arguments before they enter the device queue.
function validArguments(name, args) {
  if (!args || typeof args !== "object" || Array.isArray(args)) return false;
  const spec = tools.find(t=>t.name===name)?.inputSchema;
  if (!spec) return false;
  for (const key of Object.keys(args)) {
    const field=spec.properties[key];
    if (!field) return false;
    if (field.enum && !field.enum.includes(args[key])) return false;
    const value=args[key];
    if (field.type==="string" && (typeof value!=="string" || value.length>(key==="base64"?90000:65536))) return false;
    if (field.type==="integer" && (!Number.isSafeInteger(value) || value<(field.minimum??0) || value>(field.maximum??Number.MAX_SAFE_INTEGER))) return false;
    if ((key==="path"||key==="destination") && (typeof value!=="string" || value.length>4096)) return false;
  }
  return spec.required.every(key=>Object.hasOwn(args,key));
}
async function mcp(payload) {
  const {method,id,params={}}=payload||{};
  if (method==="initialize") return rpcResponse(id,{protocolVersion:"2025-03-26",
    capabilities:{tools:{listChanged:false}},serverInfo:{name:"oi-android",version:"0.3.1"}});
  if (method==="notifications/initialized") return null;
  if (method==="ping") return rpcResponse(id,{});
  if (method==="tools/list") return rpcResponse(id,{tools});
  if (method==="tools/call") {
    const name=params?.name, args=params?.arguments || {};
    if (!tools.some(x=>x.name===name)) return rpcError(id,-32602,"Unknown tool");
    if (!validArguments(name,args)) return rpcError(id,-32602,"Invalid tool arguments");
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
  try {
  const u=new URL(req.url,"http://localhost");
  // No browser CORS, no public file serving.
  if(req.method==="GET" && u.pathname==="/health")
    return json(res,200,{ok:true,service:"oi-android-bridge"});
  // Optional second protocol for other owner-approved automation clients.
  // Independent bearer token: the MCP route key does not grant REST access.
  if(u.pathname.startsWith("/api/v1/")){
    if (!API_TOKEN || !sameSecret(req.headers.authorization || "", "Bearer " + API_TOKEN))
      return json(res,401,{error:"Unauthorized"});
    if(u.pathname==="/api/v1/tools" && req.method==="GET")
      return json(res,200,{tools});
    if(u.pathname==="/api/v1/status" && req.method==="GET") {
      const reply=await mcp({method:"tools/call",id:1,params:{name:"phone_status",arguments:{}}});
      return json(res,200,JSON.parse(reply.result.content[0].text));
    }
    if(u.pathname==="/api/v1/run" && req.method==="POST") {
      const body=await readBody(req);
      const reply=await mcp({method:"tools/call",id:1,params:{name:body?.name,arguments:body?.args||{}}});
      if(reply.error) return json(res,400,{error:reply.error.message});
      return json(res,reply.result.isError?503:200,reply.result);
    }
    return json(res,404,{error:"Not found"});
  }
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
  } catch (e) {
    if (!res.headersSent) return json(res,400,{error:"Invalid request"});
    res.end();
  }
});
server.requestTimeout=35000;
server.listen(PORT,()=>console.log("OI Android bridge listening on "+PORT+
  (PUBLIC_ORIGIN?" | MCP: "+PUBLIC_ORIGIN.replace(/\/$/,"")+"/mcp/<private-key>":"")));
