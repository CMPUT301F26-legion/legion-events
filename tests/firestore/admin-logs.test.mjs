// Rules checks for S20 Admin logs (US 03.08.01, 03.12.01): an admin can list every
// notification, owner notice, conversation and message; other users cannot.
import { readFileSync } from 'node:fs';
import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { collection, doc, getDocs, setDoc, serverTimestamp, setLogLevel } from 'firebase/firestore';

const endpoint = process.env.FIRESTORE_EMULATOR_HOST;
assert.ok(endpoint, 'Set FIRESTORE_EMULATOR_HOST to run the rules tests.');
const [host, port] = endpoint.split(':');
const rules = readFileSync(new URL('../../firestore.rules', import.meta.url), 'utf8');
let environment;
setLogLevel('silent');

async function seed(path, data) {
  await environment.withSecurityRulesDisabled(context => setDoc(doc(context.firestore(), path), data));
}

function profile(role) {
  return { name: role, email: '', phone: '', role, photoId: null, notificationsEnabled: true, fcmToken: null,
    disabled: false, createdAt: serverTimestamp(), updatedAt: serverTimestamp() };
}

const db = uid => environment.authenticatedContext(uid).firestore();

before(async () => {
  environment = await initializeTestEnvironment({
    projectId: `demo-admin-logs-${process.pid}`,
    firestore: { host, port: Number(port), rules },
  });
  await seed('users/admin', profile('admin'));
  await seed('users/owner', profile('user'));
  await seed('users/entrant', profile('user'));
  await seed('notifications/n1', { type: 'lottery_won', toUserId: 'entrant', lotteryId: 'L1', offerId: 'L1_entrant', createdAt: serverTimestamp() });
  await seed('ownerNotices/o1', { ownerId: 'owner', lotteryId: 'L1', message: 'Hi', recipientIds: ['entrant'], createdAt: serverTimestamp(), updatedAt: serverTimestamp() });
  await seed('conversations/c1', { campsiteId: 'camp', lotteryId: null, reservationId: null, entrantId: 'entrant', ownerId: 'owner', lastMessage: '', createdAt: serverTimestamp(), updatedAt: serverTimestamp() });
  await seed('messages/m1', { conversationId: 'c1', senderId: 'owner', receiverId: 'entrant', text: 'Gate code', read: false, createdAt: serverTimestamp(), updatedAt: serverTimestamp() });
});

after(async () => { if (environment) await environment.cleanup(); });

test('an admin can list every collection the logs read', async () => {
  for (const name of ['notifications', 'ownerNotices', 'conversations', 'messages']) {
    const snapshot = await assertSucceeds(getDocs(collection(db('admin'), name)));
    assert.equal(snapshot.size, 1, name);
  }
});

test('a non-admin cannot list them', async () => {
  for (const name of ['notifications', 'ownerNotices', 'conversations', 'messages']) {
    await assertFails(getDocs(collection(db('owner'), name)));
  }
});
