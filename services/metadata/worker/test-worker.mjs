import test from 'node:test';
import assert from 'node:assert/strict';
import {DatabaseSync} from 'node:sqlite';
import {readFileSync} from 'node:fs';
import {execFileSync} from 'node:child_process';
import worker,{identity,parse,requestBody,lookup,Catalog,cacheKey,searchRecords} from './worker.mjs';

const book=identity({title:'Saga test - T01',series:'Saga test',number:'01'});
const fields={matched:true,completed:true,series:'Saga test',number:'1',title:'Titre fictif',artist:'Artiste',writer:'Auteur',publisher:'Éditeur',date:'2020',isbn:'',genre:'Aventure',synopsis:'Résumé fictif.',sourceIds:['ref']};
function response(details=fields) {return {outputs:[{type:'tool.execution',name:'web_search',completed_at:'test',info:{result:JSON.stringify({ref:{url:'https://publisher.example/tome-1',title:'Éditeur',snippets:['Texte de test.']}})}},{type:'message.output',role:'assistant',content:JSON.stringify(details)}]};}
class Database {
  constructor(){this.raw=new DatabaseSync(':memory:');this.raw.exec(readFileSync(new URL('./schema.sql',import.meta.url),'utf8'));}
  prepare(sql){const db=this;const statement=values=>({bind(...bound){return statement(bound);},async first(){return db.raw.prepare(sql).get(...values)??null;},async run(){return db.raw.prepare(sql).run(...values);}});return statement([]);}
  async batch(statements){this.raw.exec('BEGIN');try {const results=[];for(const statement of statements)results.push(await statement.run());this.raw.exec('COMMIT');return results;}catch(e){this.raw.exec('ROLLBACK');throw e;}}
}
test('Private paths, account fields and prototype keys are rejected',()=>{
  for(const body of [{title:'file:/Users/private.pdf'},{title:'BD',device:'private'},{title:'BD',constructor:'x'},{title:'a\n'},{title:'x'.repeat(241)},{}])assert.throws(()=>identity(body));
  const request=requestBody(book);assert.equal(request.store,false);assert.deepEqual(request.tools,[{type:'web_search'}]);
});
test('Valid cited fields accepted; wrong tome/ISBN and invented sources refused',()=>{
  assert.equal(parse(response(),book).matched,true);
  assert.deepEqual(parse(response({...fields,number:'2'}),book),{matched:false});
  assert.deepEqual(parse(response(),{...book,isbn:'9781234567890'}),{matched:false});
  for(const change of [{sourceIds:['invented']},{completed:false},{sourceIds:[]}])assert.throws(()=>parse(response({...fields,...change}),book));
  for(const url of ['http://publisher.example','https://user@publisher.example']){
    const r=response();r.outputs[0].info.result={ref:{url,title:'bad'}};assert.throws(()=>parse(r,book));
  }
});
test('Requested encyclopedia, comic and retail sources are accepted with actual citations',()=>{
  for(const url of ['https://www.bdtheque.com/album','https://www.bedetheque.com/BD-test.html','https://fr.wikipedia.org/wiki/Test','https://www.amazon.fr/dp/9056460021']) {
    const r=response();r.outputs[0].info.result={ref:{url,title:'Source'}};
    assert.equal(parse(r,book).sources[0].url,url);
    assert.deepEqual(parse({...r,outputs:[r.outputs[0],response({...fields,number:'2'}).outputs[1]]},book),{matched:false});
  }
});
test('Formatting cannot cite a new source or repeat the search',async()=>{
  const searched=response();searched.outputs[1].content='bad JSON';let calls=0;
  const fetcher=async(url,request)=>{calls++;const body=JSON.parse(request.body);if(calls===1)return Response.json(searched);assert.equal(body.tools,undefined);return Response.json({outputs:[response().outputs[1]]});};
  assert.equal((await lookup(book,'fictitious-key',fetcher)).matched,true);assert.equal(calls,2);
  await assert.rejects(()=>lookup(book,'fictitious-key',async()=>Response.json({}, {status:429})));
  await assert.rejects(()=>lookup(book,'fictitious-key',async()=>Response.json({outputs:[{...response().outputs[1],content:'bad'}]})));
});
test('Citation labels escaped and cache identity separates volumes and editions',async()=>{
  const r=response();r.outputs[0].info.result={ref:{url:'https://publisher.example/book',title:'<script>bad</script>'}};
  assert.ok(parse(r,book).searchAttribution.includes('&lt;script&gt;'));
  assert.equal(await cacheKey(book),await cacheKey({...book,title:'SAGA TEST - T01',number:'1'}));
  assert.notEqual(await cacheKey(book),await cacheKey({...book,number:'2'}));
  assert.notEqual(await cacheKey(book),await cacheKey({...book,isbn:'9781234567890'}));
});
test('Shared SQLite cache persists across catalog instances without user identity',async()=>{
  const db=new Database();let calls=0;const provider=async()=>{calls++;return parse(response(),book);};
  assert.equal((await new Catalog(db,'test',provider).query(book)).matched,true);
  assert.equal((await new Catalog(db,'test',provider).query(book)).matched,true);assert.equal(calls,1);
  assert.equal(db.raw.prepare('SELECT calls FROM quota').get().calls,1);
});
test('Shared database is checked before AI even when its quota is exhausted',async()=>{
  const db=new Database();let calls=0;
  const found=await new Catalog(db,'test',async()=>{calls++;return parse(response(),book);}).query(book);
  db.raw.prepare('UPDATE quota SET calls=100').run();
  const otherUser=new Catalog(db,'test',async()=>{calls++;throw Error('AI unavailable');});
  const equivalent={...book,title:'SAGA TEST T01',series:'SAGA TEST',number:'1'};
  assert.deepEqual(await otherUser.query(equivalent),found);
  assert.equal(calls,1);assert.equal(db.raw.prepare('SELECT calls FROM quota').get().calls,100);
  await assert.rejects(()=>otherUser.query({...equivalent,number:'2'}));
  assert.equal(calls,1);
});
test('Daily quota is atomic across simultaneous clients and survives failed calls',async()=>{
  const db=new Database(),day=Math.floor(Date.now()/86400000);db.raw.prepare('INSERT INTO quota VALUES (?,100)').run(day);let calls=0;
  await assert.rejects(()=>new Catalog(db,'test',async()=>{calls++;}).query(book));assert.equal(calls,0);
  db.raw.exec('DELETE FROM quota;DELETE FROM pace');
  await assert.rejects(()=>new Catalog(db,'test',async()=>{throw Error('provider');}).query(book));
  assert.equal(db.raw.prepare('SELECT calls FROM quota').get().calls,1);
  db.raw.exec('DELETE FROM pace');db.raw.prepare('UPDATE quota SET calls=99').run();
  const results=await Promise.allSettled([new Catalog(db,'test',async()=>{calls++;return {matched:false};}).query(book),new Catalog(db,'test',async()=>{calls++;return {matched:false};}).query({...book,number:'2'})]);
  assert.equal(results.filter(r=>r.status==='fulfilled').length,1);assert.equal(calls,1);
  assert.equal(db.raw.prepare('SELECT calls FROM quota').get().calls,100);
});
test('HTTP rejects uploads/unknown fields and reports configuration failure',async()=>{
  let r=await worker.fetch(new Request('https://example/v1/books',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({...book,deviceId:'x'})}),{});assert.equal(r.status,400);
  r=await worker.fetch(new Request('https://example/v1/books',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(book)}),{});assert.equal(r.status,503);
  r=await worker.fetch(new Request('https://example/health'),{});assert.equal((await r.json()).ready,false);
});
test('Python and Worker contracts agree on identical public evidence',()=>{
  const input=JSON.stringify({response:response(),book});
  const script='import sys,json;sys.path.insert(0,"services/metadata");from mistral_provider import parse;data=json.load(sys.stdin);r=parse(data["response"],data["book"]);r.pop("checkedAt");print(json.dumps(r,ensure_ascii=False))';
  const python=JSON.parse(execFileSync('python3',['-c',script],{input,encoding:'utf8',cwd:new URL('../../../',import.meta.url)}));
  const javascript=parse(response(),book);delete javascript.checkedAt;assert.deepEqual(javascript,python);
  const records=searchRecords(response());assert.equal(requestBody(book,records).tools,undefined);
});

test('Dashboard deployment serves corresponding source bytes without a secret',async()=>{
  const bytes=Buffer.from('Public source archive test');
  const r=await worker.fetch(new Request('https://example/source'),{SOURCE_B64:bytes.toString('base64')});
  assert.equal(r.status,200);assert.equal(r.headers.get('Content-Type'),'application/zip');
  assert.deepEqual(Buffer.from(await r.arrayBuffer()),bytes);
  assert.equal((await worker.fetch(new Request('https://example/source'),{})).status,503);
});

test('Expired legacy successes survive and are promoted even without an AI key',async()=>{
  const db=new Database(),id=await cacheKey(book),body=JSON.stringify(parse(response(),book));
  db.raw.prepare('INSERT INTO cache VALUES (?,?,?)').run(id,1,body);
  const result=await new Catalog(db,'',async()=>{throw Error('must not search');}).query(book);
  assert.equal(result.matched,true);assert.equal(db.raw.prepare('SELECT expires FROM cache').get().expires,0);
  assert.equal(db.raw.prepare('SELECT count(*) AS n FROM quota').get().n,0);
  const r=await worker.fetch(new Request('https://example/v1/books',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(book)}),{DB:db});
  assert.equal(r.status,200);assert.deepEqual(await r.json(),result);
});
test('More than 2000 successful fiches and legacy positives survive cleanup',async()=>{
  const db=new Database(),body=JSON.stringify(parse(response(),book));
  const insert=db.raw.prepare('INSERT INTO cache VALUES (?,?,?)');
  for(let i=0;i<2010;i++)insert.run(String(i).padStart(64,'0'),0,body);
  insert.run('old-positive',1,body);insert.run('old-negative',1,'{"matched":false}');
  await new Catalog(db,'test',async()=>parse(response(),book)).query(book);
  assert.equal(db.raw.prepare('SELECT count(*) AS n FROM cache').get().n,2012);
  assert.ok(db.raw.prepare("SELECT body FROM cache WHERE key='old-positive'").get());
  assert.equal(db.raw.prepare("SELECT body FROM cache WHERE key='old-negative'").get(),undefined);
  db.raw.exec(readFileSync(new URL('./migrate-durable.sql',import.meta.url),'utf8'));
  assert.equal(db.raw.prepare('SELECT count(*) AS n FROM cache WHERE expires=0').get().n,2012);
});
test('Concurrent users requesting the same album cannot start a second AI search',async()=>{
  const db=new Database();let complete,start;let calls=0;
  const begun=new Promise(resolve=>start=resolve),pending=new Promise(resolve=>complete=resolve);
  const provider=async()=>{calls++;start();await pending;return parse(response(),book);};
  const first=new Catalog(db,'test',provider).query(book);await begun;
  db.raw.exec('UPDATE pace SET expires=0');
  await assert.rejects(()=>new Catalog(db,'test',provider).query(book),/album_busy/);
  assert.equal(calls,1);complete();const result=await first;
  assert.deepEqual(await new Catalog(db,'test',provider).query(book),result);
  assert.equal(calls,1);assert.equal(db.raw.prepare('SELECT count(*) AS n FROM lookup_locks').get().n,0);
});
test('Failed AI lookup releases the album lease without resetting the quota',async()=>{
  const db=new Database();
  await assert.rejects(()=>new Catalog(db,'test',async()=>{throw Error('failed');}).query(book));
  assert.equal(db.raw.prepare('SELECT count(*) AS n FROM lookup_locks').get().n,0);
  assert.equal(db.raw.prepare('SELECT calls FROM quota').get().calls,1);
});

test('All known bibliographic clues reach the prompt without private paths',()=>{
  const enriched=identity({...book,artist:'Artiste connu',writer:'Auteur connu',publisher:'Éditeur connu',date:'2021',genre:'BD',synopsis:'Résumé connu',knownSources:'https://publisher.example/known',coverText:'Texte visible'});
  const prompt=requestBody(enriched).inputs[0].content;
  for(const clue of ['Artiste connu','Auteur connu','Éditeur connu','2021','Résumé connu','Texte visible'])assert.ok(prompt.includes(clue));
  assert.throws(()=>identity({...book,artist:'/Users/private/name'}));
  assert.throws(()=>identity({...book,coverImage:'https://private.example/image'}));
  assert.throws(()=>identity({...book,coverImage:'data:image/jpeg;base64,aGVsbG8='}));
});
test('Extra clues retry a negative cache but verified positives are shared with title-only readers',async()=>{
  const db=new Database();let calls=0;
  await new Catalog(db,'test',async()=>{calls++;return {matched:false};}).query(book);
  db.raw.exec('DELETE FROM pace');
  const enriched={...book,writer:'Auteur',publisher:'Éditeur'};
  const result=await new Catalog(db,'test',async()=>{calls++;return parse(response(),enriched);}).query(enriched);
  assert.equal(result.matched,true);assert.equal(calls,2);
  assert.deepEqual(await new Catalog(db,'',async()=>{throw Error('No AI');}).query(book),result);
  assert.equal(calls,2);
  assert.equal((await new Catalog(db,'test',async()=>{throw Error('must not reuse wrong author');}).query({...book,writer:'Autre auteur'}).catch(()=>({matched:false}))).matched,false);
});
test('Cover fallback transcribes first then verifies on the web without leaking the image into storage',async()=>{
  // Bounded SOF fixture used only with a mocked provider, never sent as a real image.
  const image='data:image/jpeg;base64,'+Buffer.from([255,216,255,192,0,17,8,0,2,0,2,3,1,17,0,2,17,0,3,17,0,255,217]).toString('base64');
  const unknown=identity({title:'Scan inconnu',coverImage:image});
  const found={title:'Titre reconnu',series:'Saga test',number:'1',isbn:'',artist:'Artiste',writer:'Auteur',publisher:'Éditeur'};
  let calls=0;
  const fake=async(url,options)=>{
    calls++;const body=JSON.parse(options.body);
    if(body.inputs[0].content instanceof Array) {
      assert.equal(body.store,false);assert.equal(body.inputs[0].content[1].image_url,image);
      return Response.json({outputs:[{type:'message.output',role:'assistant',finish_reason:'stop',content:JSON.stringify(found)}]});
    }
    assert.ok(body.inputs[0].content.includes('Titre reconnu'));
    assert.ok(!body.inputs[0].content.includes('data:image'));
    return Response.json(response({...fields,title:'Titre reconnu'}));
  };
  const result=await lookup(unknown,'test',fake);
  assert.equal(calls,2);assert.equal(result.matched,true);assert.equal(result.identifiedFromCover,true);
  assert.deepEqual(result.requestedIdentity,{title:'Scan inconnu',series:'',number:'',isbn:''});
  const db=new Database();await new Catalog(db,'test',async()=>result).query(unknown);
  assert.ok(!JSON.stringify(db.raw.prepare('SELECT * FROM cache').all()).includes('data:image'));
  assert.equal(db.raw.prepare('SELECT count(*) AS n FROM cache').get().n,1);
  assert.equal(db.raw.prepare('SELECT * FROM cache WHERE key=?').get(await cacheKey(unknown)),undefined);
  const wrong=identity({...unknown,number:'2'});
  const rejected=await lookup(wrong,'test',fake);assert.equal(rejected.matched,false);assert.equal(calls,3);
});
