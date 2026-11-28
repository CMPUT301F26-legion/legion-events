// End registration is a narrowly scoped owner action, independent of legacy booking dates.
import { readFileSync } from 'node:fs';
import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, serverTimestamp, Timestamp, setLogLevel } from 'firebase/firestore';

const endpoint = process.env.FIRESTORE_EMULATOR_HOST;
assert.ok(endpoint, 'Set FIRESTORE_EMULATOR_HOST to run the rules tests.');
const [host, port] = endpoint.split(':');
assert.ok(['127.0.0.1', 'localhost'].includes(host), 'Use a local emulator.');
const rules = readFileSync(process.env.LOCAL_DRAW_RULES_PATH || new URL('../../firestore.rules', import.meta.url), 'utf8');
let environment;
setLogLevel('silent');
const minute = 60_000;
const at = offset => Timestamp.fromMillis(Date.now() + offset);
const client = uid => environment.authenticatedContext(uid).firestore();

async function seed(id, overrides = {}) {
  await environment.withSecurityRulesDisabled(async context => {
    await setDoc(doc(context.firestore(), 'lotteries', id), {
      ownerId: 'owner', campsiteIds: ['camp'], registrationStartAt: at(-2 * minute),
      registrationEndAt: at(10 * minute), bookingStartAt: at(-minute), bookingEndAt: at(60 * minute),
      winnerCount: 1, waitlistLimit: 100, entrantCount: 2, status: 'open', winnersDrawn: false,
      createdAt: serverTimestamp(), updatedAt: serverTimestamp(), ...overrides,
    });
  });
}

const close = (id, uid = 'owner', overrides = {}) => updateDoc(doc(client(uid), 'lotteries', id), {
  registrationEndAt: serverTimestamp(), updatedAt: serverTimestamp(), ...overrides,
});

before(async () => {
  environment = await initializeTestEnvironment({
    projectId: `demo-end-registration-${process.pid}`, firestore: { host, port: Number(port), rules },
  });
  await environment.withSecurityRulesDisabled(async context => {
    for (const uid of ['owner', 'other', 'disabled']) {
      await setDoc(doc(context.firestore(), 'users', uid), { disabled: uid === 'disabled', role: 'user' });
    }
  });
});
after(async () => { if (environment) await environment.cleanup(); });

test('owner closes registration with entrants and preserves all other lottery fields', async () => {
  await seed('legacy');
  const ref = doc(client('owner'), 'lotteries', 'legacy');
  const before = (await getDoc(ref)).data();
  await assertSucceeds(close('legacy'));
  const after = (await getDoc(ref)).data();
  assert.ok(after.registrationEndAt.toMillis() < before.registrationEndAt.toMillis());
  assert.ok(after.registrationEndAt.isEqual(after.updatedAt));
  for (const key of Object.keys(before).filter(key => !['registrationEndAt', 'updatedAt'].includes(key))) {
    assert.deepEqual(after[key], before[key], key);
  }
});

test('closing cannot change booking dates, winners, ownership, campsites, or entrant counts', async () => {
  await seed('immutable');
  for (const fields of [
    { bookingStartAt: at(-30_000) }, { bookingEndAt: at(120 * minute) },
    { winnerCount: 2 }, { ownerId: 'other' }, { campsiteIds: ['other-camp'] },
    { entrantCount: 0 }, { winnersDrawn: true }, { status: 'closed' },
  ]) {
    await assertFails(close('immutable', 'owner', fields));
  }
  await assertFails(close('immutable', 'owner', { registrationEndAt: at(-10_000) }));
  await assertFails(close('immutable', 'owner', { updatedAt: at(-10_000) }));
});

test('only the active signed-in owner may close registration', async () => {
  await seed('private');
  await assertFails(close('private', 'other'));
  await assertFails(updateDoc(doc(environment.unauthenticatedContext().firestore(), 'lotteries', 'private'), {
    registrationEndAt: serverTimestamp(), updatedAt: serverTimestamp(),
  }));
  await seed('disabled', { ownerId: 'disabled' });
  await assertFails(close('disabled', 'disabled'));
});

test('unopened, already closed, and drawn lotteries cannot be closed again', async () => {
  for (const [id, fields] of [
    ['unopened', { registrationStartAt: at(minute) }],
    ['closed', { registrationEndAt: at(-minute) }],
    ['drawn', { winnersDrawn: true }],
    ['inactive', { status: 'closed' }],
  ]) {
    await seed(id, fields);
    await assertFails(close(id));
  }
});
