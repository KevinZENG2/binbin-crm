import test from 'node:test';
import assert from 'node:assert/strict';
import { previewHasExpired, readPreviewInfo } from '../src/previewInfo';

test('ordinary deployments and incomplete metadata have no temporary banner', () => {
  for (const value of [null, {}, {status:'UP'}, {preview:{expiresAt:'2026-10-29T08:00:00Z'}},
    {preview:{ephemeral:'true',syntheticDataOnly:true,expiresAt:'2026-10-29T08:00:00Z'}}]) {
    assert.equal(readPreviewInfo(value), null);
  }
});

test('temporary metadata normalizes time zones to the same deadline', () => {
  assert.deepEqual(readPreviewInfo({preview:{ephemeral:true,syntheticDataOnly:true,
    expiresAt:'2026-10-29T16:00:00+08:00'}}), {expiresAt:'2026-10-29T08:00:00.000Z'});
});

test('invalid deadlines cannot produce an unlimited temporary session', () => {
  assert.equal(readPreviewInfo({preview:{ephemeral:true,syntheticDataOnly:true,expiresAt:'invalid'}}),null);
});

test('the preview is expired exactly at its deadline', () => {
  const info={expiresAt:'2026-10-29T08:00:00.000Z'},deadline=Date.parse(info.expiresAt);
  assert.equal(previewHasExpired(info,deadline-1),false);
  assert.equal(previewHasExpired(info,deadline),true);
});
