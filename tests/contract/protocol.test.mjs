import test from 'node:test';
import assert from 'node:assert/strict';
import {validateMessage,negotiate,LIMITS} from '../../protocol/src/index.mjs';
const cue={cueId:'c',originalText:'hello',origin:'DIRECT',observedAtMediaMs:0};
const msg=()=>({type:'SNAPSHOT',sessionId:'s',revision:0,seq:1,cues:[{...cue}]});

test('lossy JSON values, cycles, secret fields and invalid minor are rejected',()=>{
  const cycle=msg(); cycle.extra=cycle;
  assert.throws(()=>validateMessage(cycle));
  assert.throws(()=>validateMessage({...msg(),cues:[{...cue,endMediaMs:NaN}]}));
  assert.throws(()=>validateMessage({...msg(),cues:[{...cue,sourceUrl:'redacted'}]}));
  assert.throws(()=>validateMessage({...msg(),extra:new Date()}));
  assert.throws(()=>negotiate({major:1,minor:-1}));
});
test('OPEN begins sequence 1 and descriptor fields are typed',()=>{
  const open={type:'OPEN',sessionId:'s',seq:1,revision:0,
    descriptor:{inputMode:'DIRECT'},clock:{positionMediaMs:0,playing:true,speed:1}};
  assert.doesNotThrow(()=>validateMessage(open));
  assert.throws(()=>validateMessage({...open,seq:2}));
  assert.throws(()=>validateMessage({...open,descriptor:{inputMode:'DIRECT',targetLanguage:42}}));
});
test('deep immutable owned DTO, unknown cue bounds remain unknown',()=>{const input=msg();const out=validateMessage(input);input.cues[0].originalText='changed';assert.equal(out.cues[0].originalText,'hello');assert.equal(out.cues[0].startMediaMs,undefined);assert.ok(Object.isFrozen(out.cues[0]));assert.throws(()=>{out.cues.push(cue);});});
test('major negotiation DIRECT only',()=>{assert.throws(()=>negotiate({major:2}),e=>e.code==='PROTOCOL_MISMATCH');assert.deepEqual(negotiate({major:1}).inputModes,['DIRECT']);});
test('UTF16 unit and serialized UTF8 bounds both enforced',()=>{const m=msg();m.cues[0].originalText='😀'.repeat(2048);assert.doesNotThrow(()=>validateMessage(m));m.cues[0].originalText+='a';assert.throws(()=>validateMessage(m));const n=msg();n.extra='x'.repeat(LIMITS.maxMessageBytes);assert.throws(()=>validateMessage(n));});
test('negative DTO cases fail',()=>{for(const patch of [{revision:-1},{seq:0},{type:'BOGUS'},{cues:Array(33).fill(cue)},{cues:[cue,cue]},{cues:[{...cue,origin:'ASR'}]},{cues:[{...cue,startMediaMs:3,endMediaMs:2}]},{cues:[{...cue,observedAtMediaMs:NaN}]},{cues:[{...cue,confidence:2}]}])assert.throws(()=>validateMessage({...msg(),...patch}));});
