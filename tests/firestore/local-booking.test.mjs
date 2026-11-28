// Foreground owner permissions, atomic transactions and concurrent devices. Synthetic emulator data only.
import { readFileSync } from 'node:fs';
import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { collection, doc, getDoc, getDocs, query, where, runTransaction, setDoc, updateDoc,
  writeBatch, serverTimestamp, deleteField, Timestamp, setLogLevel } from 'firebase/firestore';
const [host, port] = (process.env.FIRESTORE_EMULATOR_HOST ?? '').split(':');
assert.ok(['127.0.0.1', 'localhost'].includes(host));
let env, sequence = 0;
const day = 86400000;
const tomorrow = (Math.floor(Date.now() / day) + 1) * day;
const key = millis => new Date(millis).toISOString().slice(0, 10);
const db = (uid = 'owner') => env.authenticatedContext(uid).firestore();
const seed = (path, fields) => env.withSecurityRulesDisabled(c => setDoc(doc(c.firestore(), path), fields));
setLogLevel('silent');
before(async () => {
  env = await initializeTestEnvironment({ projectId: `demo-local-booking-${process.pid}`, firestore: {
    host, port: Number(port), rules: readFileSync(new URL('../../firestore.rules', import.meta.url), 'utf8'),
  } });
  for (const uid of ['owner', 'other', 'alice', 'bob', 'disabled']) await seed(`users/${uid}`, { disabled: uid === 'disabled', role: 'user' });
});
after(async () => { await env?.cleanup(); });

async function fixture({ count = 2, userId = 'alice', siteId = `site-${++sequence}` } = {}) {
  const id = `booking-${++sequence}`, lotteryId = `lottery-${sequence}`, offerId = `${lotteryId}_${userId}`;
  const nights = Array.from({ length: count }, (_, i) => key(tomorrow + i * day));
  const createdAt = Timestamp.fromMillis(Date.now() - 1000), expiresAt = Timestamp.fromMillis(Date.now() + day);
  await seed(`campsites/${siteId}`, { ownerId: 'owner', active: true, maxGuests: 4, pricePerNight: 1500, availableNights: nights });
  await seed(`lotteries/${lotteryId}`, { ownerId: 'owner', campsiteIds: [siteId], winnersDrawn: true,
    bookingStartAt: Timestamp.fromMillis(Date.now() - day), bookingEndAt: expiresAt });
  await seed(`offers/${offerId}`, { lotteryId, userId, campsiteIds: [siteId], status: 'pending', response: 'accept',
    reservationId: id, respondedAt: createdAt, expiresAt });
  const request = { lotteryId, offerId, campsiteId: siteId, userId, ownerId: 'owner', startDate: Timestamp.fromMillis(tomorrow),
    endDate: Timestamp.fromMillis(tomorrow + count * day), guestCount: 2, totalPrice: 1500 * count,
    rulesAccepted: true, cancellationRequested: false, status: 'requested', createdAt, updatedAt: createdAt };
  await seed(`reservations/${id}`, request);
  await seed(`waitlist/${lotteryId}_${userId}`, { lotteryId, userId, status: 'won' });
  return { id, lotteryId, offerId, siteId, nights, request };
}
const notice = (f, outcome) => ({ toUserId: f.request.userId, fromUserId: null, lotteryId: f.lotteryId, reservationId: f.id,
  type: `reservation_${outcome}`, title: `Reservation ${outcome}`,
  body: outcome === 'confirmed' ? 'Your campsite reservation is confirmed. View your booking details.' : 'Your reservation has been cancelled and its dates released.',
  read: false, createdAt: serverTimestamp(), updatedAt: serverTimestamp() });
async function confirm(f, client = db()) {
  return runTransaction(client, async tx => {
    const reference = doc(client, `reservations/${f.id}`);
    const current = (await tx.get(reference)).data();
    if (current.status !== 'requested') return false;
    await tx.get(doc(client, `offers/${f.offerId}`));
    await tx.get(doc(client, `lotteries/${f.lotteryId}`));
    await tx.get(doc(client, `campsites/${f.siteId}`));
    const refs = f.nights.map(date => doc(client, `bookedNights/${f.siteId}_${date}`));
    const locks = await Promise.all(refs.map(ref => tx.get(ref)));
    if (locks.some(lock => lock.exists())) {
      tx.update(reference, { status: 'rejected', updatedAt: serverTimestamp() });
      tx.update(doc(client, `offers/${f.offerId}`), { response: null, reservationId: deleteField(), respondedAt: deleteField(), updatedAt: serverTimestamp() });
      return false;
    }
    refs.forEach((ref, i) => tx.set(ref, { campsiteId: f.siteId, reservationId: f.id, date: f.nights[i] }));
    tx.update(reference, { status: 'confirmed', updatedAt: serverTimestamp() });
    tx.update(doc(client, `offers/${f.offerId}`), { status: 'used', updatedAt: serverTimestamp() });
    tx.set(doc(client, `notifications/reservation_confirmed_${f.id}`), notice(f, 'confirmed'));
    return true;
  });
}

test('owner transaction confirms all nights, consumes acceptance and creates an entrant receipt once', async () => {
  const f = await fixture();
  assert.equal(await assertSucceeds(confirm(f)), true);
  assert.equal(await assertSucceeds(confirm(f)), false);
  assert.equal((await getDoc(doc(db('alice'), `reservations/${f.id}`))).data().status, 'confirmed');
  assert.equal((await getDoc(doc(db('alice'), `offers/${f.offerId}`))).data().status, 'used');
  assert.equal((await getDoc(doc(db('alice'), `notifications/reservation_confirmed_${f.id}`))).data().reservationId, f.id);
  for (const date of f.nights) assert.equal((await getDoc(doc(db(), `bookedNights/${f.siteId}_${date}`))).data().reservationId, f.id);
});
test('the full 366-night contract fits the owner rules document-access budget', async () => {
  const f = await fixture({ count: 366 });
  await assertSucceeds(confirm(f));
  const locks = await getDocs(query(collection(db(), 'bookedNights'), where('campsiteId', '==', f.siteId)));
  assert.equal(locks.size, 366);
});
test('entrant, foreign owner and disabled entrant cannot authorize confirmation', async () => {
  await assertFails(confirm(await fixture(), db('alice')));
  await assertFails(confirm(await fixture(), db('other')));
  await assertFails(confirm(await fixture({ userId: 'disabled' })));
});
test('eligibility probes expose no private profile and reject disabled, unrelated and foreign entrants', async () => {
  const f = await fixture();
  const path = uid => `lotteries/${f.lotteryId}/bookingEligibility/${uid}`;
  assert.equal((await assertSucceeds(getDoc(doc(db(), path('owner'))))).exists(), false);
  assert.equal((await assertSucceeds(getDoc(doc(db(), path('alice'))))).exists(), false);
  await seed(`waitlist/${f.lotteryId}_disabled`, { lotteryId: f.lotteryId, userId: 'disabled', status: 'lost' });
  await assertFails(getDoc(doc(db(), path('disabled'))));
  await assertFails(getDoc(doc(db(), path('bob'))));
  await assertFails(getDoc(doc(db('other'), path('alice'))));
  await assertFails(getDoc(doc(db(), 'users/alice')));
  await assertFails(setDoc(doc(db(), path('alice')), { disabled: false }));
});
test('two owner devices competing for one stay confirm one request and reopen the losing offer', async () => {
  const first = await fixture();
  const second = await fixture({ userId: 'bob', siteId: first.siteId });
  const results = await Promise.all([confirm(first), confirm(second)]);
  assert.equal(results.filter(Boolean).length, 1);
  const loser = results[0] ? second : first;
  assert.equal((await getDoc(doc(db(), `reservations/${loser.id}`))).data().status, 'rejected');
  const offer = (await getDoc(doc(db(), `offers/${loser.offerId}`))).data();
  assert.equal(offer.response, null);
  assert.equal(offer.status, 'pending');
  assert.equal(offer.reservationId, undefined);
});
test('owner cannot overwrite an occupied night, alter accepted dates, or lock checkout or an unrelated site', async () => {
  const f = await fixture();
  await confirm(f);
  await assertFails(setDoc(doc(db(), `bookedNights/${f.siteId}_${f.nights[0]}`), { campsiteId: f.siteId, reservationId: 'forged', date: f.nights[0] }));
  await assertFails(updateDoc(doc(db(), `reservations/${f.id}`), { endDate: Timestamp.fromMillis(tomorrow + 3 * day), updatedAt: serverTimestamp() }));
  const next = await fixture();
  for (const [siteId, date] of [[next.siteId, key(tomorrow + 2 * day)], ['foreign', next.nights[0]]]) {
    const client = db(), batch = writeBatch(client);
    batch.update(doc(client, `reservations/${next.id}`), { status: 'confirmed', updatedAt: serverTimestamp() });
    batch.update(doc(client, `offers/${next.offerId}`), { status: 'used', updatedAt: serverTimestamp() });
    batch.set(doc(client, `bookedNights/${siteId}_${date}`), { campsiteId: siteId, reservationId: next.id, date });
    await assertFails(batch.commit());
  }
});

async function release(f, rejected = false, client = db()) {
  const batch = writeBatch(client);
  for (const date of f.nights) batch.delete(doc(client, `bookedNights/${f.siteId}_${date}`));
  if (rejected) batch.set(doc(client, `reservationReleases/${f.id}`), { reservationId: f.id, processedAt: serverTimestamp() });
  else {
    batch.update(doc(client, `reservations/${f.id}`), { status: 'cancelled', cancellationRequested: false, updatedAt: serverTimestamp() });
    batch.set(doc(client, `notifications/reservation_cancelled_${f.id}`), notice(f, 'cancelled'));
  }
  return batch.commit();
}
test('confirmed cancellation releases dates and saves a receipt, but cannot be forged or delete a rebooked night', async () => {
  const f = await fixture();
  await confirm(f);
  await assertFails(release(f));
  await updateDoc(doc(db('alice'), `reservations/${f.id}`), { cancellationRequested: true, updatedAt: serverTimestamp() });
  await assertFails(release(f, false, db('other')));
  await assertSucceeds(release(f));
  assert.equal((await getDoc(doc(db('alice'), `reservations/${f.id}`))).data().status, 'cancelled');
  await seed(`bookedNights/${f.siteId}_${f.nights[0]}`, { campsiteId: f.siteId, reservationId: 'new-booking', date: f.nights[0] });
  await assertFails(release(f));
});
test('owner rejection cleanup is immutable and cannot clear a newer pending acceptance', async () => {
  const f = await fixture();
  await confirm(f);
  await updateDoc(doc(db(), `reservations/${f.id}`), { status: 'rejected', updatedAt: serverTimestamp() });
  await assertSucceeds(release(f, true));
  await assertFails(release(f, true));
  const pending = await fixture();
  await updateDoc(doc(db(), `reservations/${pending.id}`), { status: 'rejected', updatedAt: serverTimestamp() });
  await seed(`offers/${pending.offerId}`, { ...((await getDoc(doc(db(), `offers/${pending.offerId}`))).data()), reservationId: 'new-attempt' });
  await assertFails(updateDoc(doc(db(), `offers/${pending.offerId}`), { response: null, reservationId: deleteField(), respondedAt: deleteField(), updatedAt: serverTimestamp() }));
});

async function declineFixture() {
  const f = await fixture();
  await seed(`offers/${f.offerId}`, { lotteryId: f.lotteryId, userId: 'alice', campsiteIds: [f.siteId],
    status: 'pending', response: 'decline', expiresAt: Timestamp.fromMillis(Date.now() + day) });
  for (const userId of ['alice', 'bob']) await seed(`waitlist/${f.lotteryId}_${userId}`, {
    lotteryId: f.lotteryId, userId, status: userId === 'alice' ? 'won' : 'lost', updatedAt: Timestamp.now(),
  });
  return f;
}
async function replace(f, uid = 'bob', client = db()) {
  const offerId = `${f.lotteryId}_${uid}`, batch = writeBatch(client);
  const declined = (await getDoc(doc(db(), `offers/${f.offerId}`))).data();
  batch.set(doc(client, `offerReplacements/${f.offerId}`), { lotteryId: f.lotteryId, declinedOfferId: f.offerId,
    replacementUserId: uid, replacementOfferId: offerId, processedAt: serverTimestamp() });
  batch.update(doc(client, `waitlist/${offerId}`), { status: 'won', updatedAt: serverTimestamp() });
  batch.set(doc(client, `offers/${offerId}`), { lotteryId: f.lotteryId, userId: uid, campsiteIds: [f.siteId],
    status: 'pending', response: null, expiresAt: declined.expiresAt, createdAt: serverTimestamp(), updatedAt: serverTimestamp(), replacementFor: f.offerId });
  const result = { type: 'lottery_won', toUserId: uid, lotteryId: f.lotteryId, offerId, createdAt: serverTimestamp() };
  batch.set(doc(client, `notifications/lottery_replacement_${f.offerId}`), result);
  batch.set(doc(client, `lotteryNotificationOutbox/lottery_replacement_${f.offerId}`), { ...result, deliveryStatus: 'pending', updatedAt: serverTimestamp() });
  return batch.commit();
}
test('decline replaces one lost entrant once, preserving the original deadline and other winners', async () => {
  const f = await declineFixture();
  // Keep the lottery's exact original expiry; no deadline extension is allowed.
  const declined = (await getDoc(doc(db(), `offers/${f.offerId}`))).data();
  await env.withSecurityRulesDisabled(c => updateDoc(doc(c.firestore(), `lotteries/${f.lotteryId}`), { bookingEndAt: declined.expiresAt }));
  await assertSucceeds(replace(f));
  await assertFails(replace(f));
  assert.equal((await getDoc(doc(db('alice'), `offers/${f.offerId}`))).data().response, 'decline');
  assert.equal((await getDoc(doc(db('bob'), `offers/${f.lotteryId}_bob`))).data().expiresAt.toMillis(), declined.expiresAt.toMillis());
});
test('replacement cannot be issued by another entrant or for an expired decline', async () => {
  const f = await declineFixture();
  await assertFails(replace(f, 'bob', db('other')));
  const expiry = Timestamp.fromMillis(Date.now() - 1000);
  await env.withSecurityRulesDisabled(async c => {
    await updateDoc(doc(c.firestore(), `offers/${f.offerId}`), { expiresAt: expiry });
    await updateDoc(doc(c.firestore(), `lotteries/${f.lotteryId}`), { bookingEndAt: expiry });
  });
  await assertFails(replace(f));
  await assertSucceeds(setDoc(doc(db(), `offerReplacements/${f.offerId}`), { lotteryId: f.lotteryId,
    declinedOfferId: f.offerId, replacementUserId: null, replacementOfferId: null, processedAt: serverTimestamp() }));
});
