// Permissions for worker-created confirmations and private processing markers.
import { readFileSync } from 'node:fs';
import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertSucceeds, assertFails } from '@firebase/rules-unit-testing';
import { doc, setDoc, getDoc, updateDoc, deleteDoc, serverTimestamp, Timestamp, setLogLevel } from 'firebase/firestore';
const endpoint = process.env.FIRESTORE_EMULATOR_HOST;
assert.ok(endpoint, 'Set FIRESTORE_EMULATOR_HOST.');
const [host, port] = endpoint.split(':');
assert.ok(['127.0.0.1', 'localhost'].includes(host), 'Use a loopback emulator.');
let env;
setLogLevel('silent');
const db = uid => env.authenticatedContext(uid).firestore();
const seed = (path, data) => env.withSecurityRulesDisabled(c => setDoc(doc(c.firestore(), path), data));
before(async () => {
  env = await initializeTestEnvironment({ projectId: `demo-booking-worker-rules-${process.pid}`, firestore: {
    host, port: Number(port), rules: readFileSync(new URL('../../firestore.rules', import.meta.url), 'utf8'),
  } });
  for (const uid of ['entrant', 'owner', 'other']) await seed(`users/${uid}`, { disabled: false, role: 'user' });
  await seed('lotteries/draw', { ownerId: 'owner' });
  await seed('offers/draw_entrant', { lotteryId: 'draw', userId: 'entrant', status: 'used', response: 'accept', reservationId: 'booking' });
  await seed('reservations/booking', { lotteryId: 'draw', offerId: 'draw_entrant', userId: 'entrant', ownerId: 'owner', status: 'confirmed',
    cancellationRequested: false, endDate: Timestamp.fromMillis(Date.now() + 86400000) });
  await seed('notifications/reservation_confirmed_booking', { toUserId: 'entrant', fromUserId: null,
    lotteryId: 'draw', reservationId: 'booking', type: 'reservation_confirmed', title: 'Reservation confirmed',
    body: 'Your campsite reservation is confirmed. View your booking details.', read: false,
    createdAt: serverTimestamp(), updatedAt: serverTimestamp() });
});
after(async () => { if (env) await env.cleanup(); });

test('the recipient can read and mark the committed confirmation without changing its booking reference', async () => {
  const ref = doc(db('entrant'), 'notifications/reservation_confirmed_booking');
  await assertSucceeds(getDoc(ref));
  await assertSucceeds(updateDoc(ref, { read: true, updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(ref, { reservationId: 'another', updatedAt: serverTimestamp() }));
  for (const uid of ['owner', 'other']) await assertFails(getDoc(doc(db(uid), 'notifications/reservation_confirmed_booking')));
});
test('entrants and owners can read the final reservation and offer, but cannot reopen or edit it', async () => {
  for (const uid of ['entrant', 'owner']) {
    await assertSucceeds(getDoc(doc(db(uid), 'reservations/booking')));
    await assertSucceeds(getDoc(doc(db(uid), 'offers/draw_entrant')));
    await assertFails(updateDoc(doc(db(uid), 'offers/draw_entrant'), { status: 'pending', response: null, updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(db(uid), 'reservations/booking'), { startDate: Timestamp.now(), updatedAt: serverTimestamp() }));
  }
});
for (const path of ['offerReplacements/draw_entrant', 'reservationReleases/booking']) {
  test(`${path.split('/')[0]} markers are private and immutable to clients`, async () => {
    await seed(path, { processedAt: serverTimestamp() });
    for (const uid of ['entrant', 'owner', 'other']) {
      const ref = doc(db(uid), path);
      await assertFails(getDoc(ref));
      await assertFails(setDoc(ref, { processedAt: serverTimestamp() }));
      await assertFails(deleteDoc(ref));
    }
  });
}
