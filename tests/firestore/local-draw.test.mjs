// Verifies the supplied owner draw rules against the local Firestore emulator.
import { readFileSync } from 'node:fs';
import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, getDocs, collection, query, where, setDoc, updateDoc, writeBatch, serverTimestamp, Timestamp, setLogLevel } from 'firebase/firestore';

const endpoint = process.env.FIRESTORE_EMULATOR_HOST;
assert.ok(endpoint, 'Set FIRESTORE_EMULATOR_HOST to run the rules tests.');
const [host, port] = endpoint.split(':');
assert.ok(['127.0.0.1', 'localhost'].includes(host), 'Use a local emulator.');
let environment;
const rules = readFileSync(process.env.LOCAL_DRAW_RULES_PATH || new URL('../../firestore.rules', import.meta.url), 'utf8');
setLogLevel('silent');

before(async () => {
  environment = await initializeTestEnvironment({
    projectId: `demo-local-draw-${process.pid}`,
    firestore: { host, port: Number(port), rules },
  });
});
after(async () => { if (environment) await environment.cleanup(); });

/** Supplies an SDK client with the same owner UID and profile checks as the Android client. */
function client(uid = 'owner') { return environment.authenticatedContext(uid).firestore(); }

/** Seeds synthetic profiles, a closed or future lottery, and its joined entrants with rules bypassed. */
async function seed(id, count = 2, winners = 1, future = false) {
  const entrants = Array.from({ length: count }, (_, index) => `u${index}`);
  await environment.withSecurityRulesDisabled(async context => {
    const db = context.firestore();
    const batch = writeBatch(db);
    for (const uid of ['owner', 'other', ...entrants]) {
      batch.set(doc(db, 'users', uid), { disabled: false, role: 'user' });
    }
    batch.set(doc(db, 'lotteries', id), {
      ownerId: 'owner', campsiteIds: ['camp'], registrationStartAt: Timestamp.fromMillis(Date.now() - 60_000),
      registrationEndAt: Timestamp.fromMillis(Date.now() + (future ? 60_000 : -1_000)),
      bookingStartAt: Timestamp.fromMillis(Date.now() + 60_000), bookingEndAt: Timestamp.fromMillis(Date.now() + 86_400_000),
      winnerCount: winners, waitlistLimit: 100, entrantCount: count, status: 'open', winnersDrawn: false,
      createdAt: serverTimestamp(), updatedAt: serverTimestamp(),
    });
    for (const uid of entrants) {
      batch.set(doc(db, 'waitlist', `${id}_${uid}`), {
        userId: uid, lotteryId: id, status: 'joined', joinedAt: serverTimestamp(), updatedAt: serverTimestamp(),
      });
    }
    await batch.commit();
  });
  return entrants;
}

/** Builds the documented atomic write set to verify rule acceptance at the full supported capacity. */
async function drawBatch(id, entrants, winners, uid = 'owner') {
  const db = client(uid);
  const lottery = (await getDoc(doc(db, 'lotteries', id))).data();
  const batch = writeBatch(db);
  batch.set(doc(db, 'lotteryDraws', id), {
    lotteryId: id, ownerId: 'owner', entrantIds: entrants, winnerIds: winners, createdAt: serverTimestamp(),
  });
  for (const userId of entrants) {
    const won = winners.includes(userId);
    const eventId = `${id}_${userId}`;
    batch.update(doc(db, 'waitlist', eventId), { status: won ? 'won' : 'lost', updatedAt: serverTimestamp() });
    if (won) {
      batch.set(doc(db, 'offers', eventId), {
        lotteryId: id, userId, campsiteIds: lottery.campsiteIds, status: 'pending', response: null,
        expiresAt: lottery.bookingEndAt, createdAt: serverTimestamp(), updatedAt: serverTimestamp(),
      });
    }
    const result = {
      lotteryId: id, toUserId: userId, type: won ? 'lottery_won' : 'lottery_lost',
      offerId: won ? eventId : null, createdAt: serverTimestamp(),
    };
    batch.set(doc(db, 'notifications', eventId), result);
    batch.set(doc(db, 'lotteryNotificationOutbox', eventId), {
      ...result, deliveryStatus: 'pending', updatedAt: serverTimestamp(),
    });
  }
  batch.update(doc(db, 'lotteries', id), { winnersDrawn: true, updatedAt: serverTimestamp() });
  return batch.commit();
}

test('owner reads the complete closed waitlist and an absent draw manifest', async () => {
  await seed('reads');
  const db = client();
  const entries = await assertSucceeds(getDocs(query(collection(db, 'waitlist'), where('lotteryId', '==', 'reads'), where('status', '==', 'joined'))));
  assert.equal(entries.size, 2);
  assert.equal((await assertSucceeds(getDoc(doc(db, 'lotteryDraws', 'reads')))).exists(), false);
});

test('owner commits one winner, one loser, their offers and Inbox notices atomically', async () => {
  const entrants = await seed('normal');
  await assertSucceeds(drawBatch('normal', entrants, ['u0']));
  const win = await assertSucceeds(getDoc(doc(client('u0'), 'notifications', 'normal_u0')));
  const loss = await assertSucceeds(getDoc(doc(client('u1'), 'notifications', 'normal_u1')));
  assert.equal(win.data().offerId, 'normal_u0');
  assert.equal(loss.data().type, 'lottery_lost');
  await assertSucceeds(getDoc(doc(client('u0'), 'offers', 'normal_u0')));
  await assertFails(getDoc(doc(client('u1'), 'notifications', 'normal_u0')));
  await assertFails(getDoc(doc(client(), 'lotteryNotificationOutbox', 'normal_u0')));
});

test('all 100 entrants and winners fit the atomic rules access limits', async () => {
  const entrants = await seed('capacity', 100, 100);
  await assertSucceeds(drawBatch('capacity', entrants, entrants));
  assert.equal((await getDoc(doc(client(), 'lotteries', 'capacity'))).data().winnersDrawn, true);
  assert.equal((await getDoc(doc(client('u99'), 'notifications', 'capacity_u99'))).data().type, 'lottery_won');
});

test('empty lotteries can be completed without creating fictitious winners', async () => {
  const entrants = await seed('empty', 0);
  await assertSucceeds(drawBatch('empty', entrants, []));
});

test('another user cannot draw an owners lottery', async () => {
  const entrants = await seed('other-owner');
  await assertFails(drawBatch('other-owner', entrants, ['u0'], 'other'));
  assert.equal((await getDoc(doc(client(), 'lotteries', 'other-owner'))).data().winnersDrawn, false);
});

test('server time rejects a draw before registration ends', async () => {
  const entrants = await seed('future', 2, 1, true);
  await assertFails(drawBatch('future', entrants, ['u0']));
});

test('wrong winner count or missing entrants rejects the entire draw', async () => {
  const entrants = await seed('wrong-count');
  await assertFails(drawBatch('wrong-count', entrants, []));
  await assertFails(drawBatch('wrong-count', ['u0'], ['u0']));
  assert.equal((await getDoc(doc(client(), 'lotteries', 'wrong-count'))).data().winnersDrawn, false);
});

test('repeated draws and immutable manifest edits are denied', async () => {
  const entrants = await seed('repeat');
  await assertSucceeds(drawBatch('repeat', entrants, ['u0']));
  await assertFails(drawBatch('repeat', entrants, ['u1']));
  await assertFails(updateDoc(doc(client(), 'lotteryDraws', 'repeat'), { winnerIds: ['u1'] }));
});

test('two simultaneous owner draw commits cannot both succeed', async () => {
  const entrants = await seed('race');
  const attempts = await Promise.allSettled([
    drawBatch('race', entrants, ['u0']),
    drawBatch('race', entrants, ['u1']),
  ]);
  assert.equal(attempts.filter(attempt => attempt.status === 'fulfilled').length, 1);
  const manifest = (await getDoc(doc(client(), 'lotteryDraws', 'race'))).data();
  assert.equal(manifest.winnerIds.length, 1);
  const winner = manifest.winnerIds[0];
  assert.equal((await getDoc(doc(client(winner), 'notifications', `race_${winner}`))).data().type, 'lottery_won');
});

test('an existing offer is preserved and all new draw writes roll back', async () => {
  const entrants = await seed('offer-conflict');
  await environment.withSecurityRulesDisabled(context => setDoc(doc(context.firestore(), 'offers', 'offer-conflict_u0'), {
    lotteryId: 'offer-conflict', userId: 'u0', status: 'pending', response: 'decline',
  }));
  await assertFails(drawBatch('offer-conflict', entrants, ['u0']));
  assert.equal((await getDoc(doc(client(), 'lotteries', 'offer-conflict'))).data().winnersDrawn, false);
  assert.equal((await getDoc(doc(client('u0'), 'offers', 'offer-conflict_u0'))).data().response, 'decline');
});

test('owner cannot inject a standalone Inbox notice outside a new draw', async () => {
  await seed('no-draw');
  await assertFails(setDoc(doc(client(), 'notifications', 'no-draw_u0'), {
    lotteryId: 'no-draw', toUserId: 'u0', type: 'lottery_won', offerId: 'no-draw_u0', createdAt: serverTimestamp(),
  }));
});
