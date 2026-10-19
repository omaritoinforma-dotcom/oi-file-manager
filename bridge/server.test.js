"use strict";
const { test } = require("node:test");
const assert = require("node:assert/strict");
const { spawn } = require("node:child_process");
const { setTimeout: sleep } = require("node:timers/promises");
const { join } = require("node:path");

test("MCP bridge authenticates device and relays one file operation", async (t) => {
  const port = String(20000 + Math.floor(Math.random() * 20000));
  const token = "test-device-secret-abcdefghijklmnopqrstuvwxyz123456789";
  const key = "test-mcp-route-secret-abcdefghijklmnopqrstuvwxyz987654321";
  const api = "test-rest-secret-abcdefghijklmnopqrstuvwxyz0123456789";
  const proc = spawn(process.execPath, [join(__dirname, "server.js")], {
    env: {...process.env, PORT: port, OI_DEVICE_TOKEN: token, OI_MCP_ROUTE_KEY: key, OI_API_TOKEN: api},
    stdio: "ignore",
  });
  t.after(() => proc.kill("SIGTERM"));
  const base = "http://127.0.0.1:" + port;
  let ready = false;
  for (let i=0;i<60;i++) {
    if (proc.exitCode !== null) throw Error("Bridge process exited unexpectedly");
    try { const r=await fetch(base+"/health");if (r.ok) {ready=true;break;} } catch {}
    await sleep(75);
  }
  assert.equal(ready,true,"HTTP server should start");
  assert.equal((await fetch(base+"/device/next")).status,401,"Device API must require a secret");
  const deviceHeaders={"Authorization":"Bearer "+token,"X-OI-Device":"Android test"};
  assert.equal((await fetch(base+"/device/next",{headers:deviceHeaders})).status,200);
  const mcp = async (method,id,params={}) => {
    const r=await fetch(base+"/mcp/"+key,{
      method:"POST",headers:{"Content-Type":"application/json"},
      body:JSON.stringify({jsonrpc:"2.0",method,id,params})
    });
    return r.json();
  };
  assert.equal((await mcp("initialize",1)).result.serverInfo.name,"oi-android");
  const tools=(await mcp("tools/list",2)).result.tools.map(t=>t.name);
  for(const expected of ["list_roots","list_files","read_file_chunk","replace_text","trash_file"])
    assert.ok(tools.includes(expected),expected);
  assert.equal((await fetch(base+"/api/v1/tools")).status,401);
  const apiHeaders={"Authorization":"Bearer "+api};
  const restList=await fetch(base+"/api/v1/tools",{headers:apiHeaders});
  assert.equal(restList.status,200);
  assert.ok((await restList.json()).tools.length>=10);
  assert.equal((await fetch(base+"/api/v1/status",{headers:apiHeaders})).status,200);
  assert.equal((await mcp("tools/call",40,{name:"read_text",arguments:{path:17}})).error.code,-32602);
  assert.equal((await mcp("tools/call",41,{name:"write_new_text",arguments:{path:"/tmp",content:"x",extra:"unexpected"}})).error.code,-32602);
  assert.equal((await mcp("tools/call",42,{name:"begin_upload",arguments:{path:"/tmp",totalBytes:268435457,sha256:"0".repeat(64)}})).error.code,-32602);
  assert.equal((await mcp("tools/call",43,{name:"list_connections",arguments:{secret:"must-not-be-accepted"}})).error.code,-32602);
  const pending=mcp("tools/call",3,{name:"list_roots",arguments:{}});
  let command;
  for(let i=0;i<30;i++){
    command=await (await fetch(base+"/device/next",{headers:deviceHeaders})).json();
    if(command.id) break;
    await sleep(50);
  }
  assert.equal(command.action,"list_roots");
  const answer=await fetch(base+"/device/result",{
    method:"POST",
    headers:{...deviceHeaders,"Content-Type":"application/json"},
    body:JSON.stringify({id:command.id,ok:true,data:{roots:[{name:"Interno",path:"/storage/emulated/0"}]}})
  });
  assert.equal(answer.status,200);
  const result=await pending;
  assert.equal(result.result.isError,false);
  assert.match(result.result.content[0].text,/storage\/emulated\/0/);
  const remote=mcp("tools/call",50,{name:"list_files",arguments:{connectionId:"saved-sftp",path:"/uploads",offset:250}});
  let job;
  for(let i=0;i<30;i++) {
    job=await (await fetch(base+"/device/next",{headers:deviceHeaders})).json();
    if(job.id) break;
    await sleep(25);
  }
  assert.equal(job.args.connectionId,"saved-sftp");
  assert.equal(job.args.offset,250);
  await fetch(base+"/device/result",{method:"POST",headers:{...deviceHeaders,"Content-Type":"application/json"},
    body:JSON.stringify({id:job.id,ok:false,error:"Las conexiones guardadas no están autorizadas"})});
  const denied=await remote;
  assert.equal(denied.result.isError,true);
  assert.match(denied.result.content[0].text,/no están autorizadas/);
});
