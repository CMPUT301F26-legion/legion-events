// Winner acceptance contracts. Uses synthetic data in a local emulator only.
import { readFileSync } from 'node:fs';
import { before, after, test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, setDoc, updateDoc, getDoc, getDocs, query, collection, where, writeBatch, runTransaction, serverTimestamp, Timestamp, setLogLevel } from 'firebase/firestore';
const endpoint = process.env.FIRESTORE_EMULATOR_HOST;
assert.ok(endpoint, 'Set FIRESTORE_EMULATOR_HOST to run the rules tests.');
const [host, port] = endpoint.split(':');
assert.ok(['127.0.0.1', 'localhost'].includes(host), 'Use a local emulator.');
let env;
let sequence = 0;
const day = 86_400_000;
const tomorrow = (Math.floor(Date.now() / day) + 1) * day;
setLogLevel('silent');
const client = (uid = 'entrant') => env.authenticatedContext(uid).firestore();
const seed = (path, data) => env.withSecurityRulesDisabled(c => setDoc(doc(c.firestore(), path), data));
before(async () => {
  env = await initializeTestEnvironment({ projectId: `demo-booking-contract-${process.pid}`, firestore: {
    host, port: Number(port), rules: readFileSync(new URL('../../firestore.rules', import.meta.url), 'utf8'),
  } });
  for (const uid of ['entrant', 'owner', 'other', 'disabled']) {
    await seed(`users/${uid}`, { disabled: uid === 'disabled', role: 'user' });
  }
  await seed('campsites/camp', { ownerId: 'owner', active: true, maxGuests: 4, pricePerNight: 1500 });
  await seed('campsites/foreign', { ownerId: 'other', active: true, maxGuests: 4, pricePerNight: 1500 });
  await seed('campsites/inactive', { ownerId: 'owner', active: false, maxGuests: 4, pricePerNight: 1500 });
});
after(async () => { if (env) await env.cleanup(); });

async function fixture({ uid = 'entrant', expires = Date.now() + day, camp = 'camp', bookingStart = Date.now() - 1000 } = {}) {
  const lotteryId = `draw-${sequence++}`;
  const offerId = `${lotteryId}_${uid}`;
  const id = `request-${sequence}`;
  await seed(`lotteries/${lotteryId}`, { ownerId: 'owner', campsiteIds: [camp], winnersDrawn: true,
    bookingStartAt: Timestamp.fromMillis(bookingStart), bookingEndAt: Timestamp.fromMillis(expires) });
  await seed(`offers/${offerId}`, { lotteryId, userId: uid, campsiteIds: [camp], status: 'pending',
    response: null, expiresAt: Timestamp.fromMillis(expires) });
  const request = { lotteryId, offerId, campsiteId: camp, userId: uid, ownerId: 'owner',
    startDate: Timestamp.fromMillis(tomorrow), endDate: Timestamp.fromMillis(tomorrow + 2 * day),
    guestCount: 2, totalPrice: 3000, rulesAccepted: true, status: 'requested', cancellationRequested: false,
    createdAt: serverTimestamp(), updatedAt: serverTimestamp() };
  return { uid, offerId, id, request };
}
function accept(f, changes = {}, { db = client(f.uid), id = f.id, link = id } = {}) {
  const batch = writeBatch(db);
  batch.set(doc(db, `reservations/${id}`), { ...f.request, ...changes });
  batch.update(doc(db, `offers/${f.offerId}`), { response: 'accept', reservationId: link,
    respondedAt: serverTimestamp(), updatedAt: serverTimestamp() });
  return batch.commit();
}

test('explicit acceptance atomically saves a pending request, claims the offer, and permits owned recovery', async () => {
  const f = await fixture();
  await assertSucceeds(accept(f));
  const offer = (await getDoc(doc(client(), `offers/${f.offerId}`))).data();
  assert.equal(offer.status, 'pending'); // Accepted is not consumed or confirmed.
  assert.equal(offer.reservationId, f.id);
  const result = await assertSucceeds(getDocs(query(collection(client(), 'reservations'), where('userId', '==', 'entrant'), where('offerId', '==', f.offerId))));
  assert.equal(result.size, 1);
  await assertFails(getDoc(doc(client('other'), `reservations/${f.id}`)));
});
test('the booking repository transaction can read choices, save acceptance, and recover its original request', async () => {
  const f = await fixture();
  const db = client(f.uid);
  const offerRef = doc(db, `offers/${f.offerId}`);
  const requestRef = doc(db, `reservations/${f.id}`);
  const ownedRequests = () => getDocs(query(collection(db, 'reservations'),
    where('userId', '==', f.uid), where('offerId', '==', f.offerId)));
  assert.equal((await ownedRequests()).size, 0);
  await assertSucceeds(runTransaction(db, async transaction => {
    const offer = (await transaction.get(offerRef)).data();
    await transaction.get(doc(db, `lotteries/${offer.lotteryId}`));
    await transaction.get(doc(db, `campsites/${f.request.campsiteId}`));
    transaction.set(requestRef, f.request);
    transaction.update(offerRef, { response: 'accept', reservationId: f.id,
      respondedAt: serverTimestamp(), updatedAt: serverTimestamp() });
  }));
  const saved = (await assertSucceeds(ownedRequests())).docs;
  assert.deepEqual(saved.map(document => document.id), [f.id]);
  assert.equal(saved[0].data().status, 'requested');
  assert.equal(saved[0].data().rulesAccepted, true);
  const claimed = (await getDoc(offerRef)).data();
  assert.equal(claimed.reservationId, f.id);
  assert.equal(claimed.response, 'accept');
  assert.equal(claimed.status, 'pending');
  // A successful request still awaits the trusted worker; clients cannot consume the offer.
  await assertFails(updateDoc(offerRef, { status: 'used', updatedAt: serverTimestamp() }));
  await assertFails(accept(f, {}, { id: `${f.id}-duplicate` }));
});
test('standalone requests and standalone accept responses are denied', async () => {
  const f = await fixture();
  await assertFails(setDoc(doc(client(), `reservations/${f.id}`), f.request));
  await assertFails(updateDoc(doc(client(), `offers/${f.offerId}`), { response: 'accept', reservationId: f.id,
    respondedAt: serverTimestamp(), updatedAt: serverTimestamp() }));
});
test('mismatched links, an existing request link, and a second request for one offer are denied', async () => {
  const f = await fixture();
  await assertFails(accept(f, {}, { link: 'missing' }));
  await seed('reservations/existing', { ...f.request, status: 'rejected' });
  await assertFails(accept(f, {}, { id: 'existing' }));
  await assertSucceeds(accept(f));
  await assertFails(accept(f, {}, { id: `${f.id}-duplicate` }));
  await assertFails(updateDoc(doc(client(), `offers/${f.offerId}`), { response: 'decline', respondedAt: serverTimestamp(), updatedAt: serverTimestamp() }));
});
test('competing accept transactions cannot produce two pending requests', async () => {
  const f = await fixture();
  const results = await Promise.allSettled([accept(f), accept(f, {}, { id: `${f.id}-race` })]);
  assert.equal(results.filter(r => r.status === 'fulfilled').length, 1);
});
test('entrants and owners cannot confirm, consume offers, lock nights, or forge confirmation notices', async () => {
  const f = await fixture();
  await assertSucceeds(accept(f));
  for (const uid of ['entrant', 'owner']) {
    await assertFails(updateDoc(doc(client(uid), `reservations/${f.id}`), { status: 'confirmed', updatedAt: serverTimestamp() }));
    await assertFails(updateDoc(doc(client(uid), `offers/${f.offerId}`), { status: 'used', updatedAt: serverTimestamp() }));
    await assertFails(setDoc(doc(client(uid), 'bookedNights/camp_2027-07-16'), { campsiteId: 'camp', reservationId: f.id, date: '2027-07-16' }));
    await assertFails(setDoc(doc(client(uid), `notifications/reservation_confirmed_${f.id}`), { toUserId: 'entrant', type: 'reservation_confirmed', lotteryId: f.request.lotteryId, reservationId: f.id }));
  }
});
for (const [name, changes] of Object.entries({
  'unaccepted policies': { rulesAccepted: false }, 'zero guests': { guestCount: 0 },
  'too many guests': { guestCount: 5 }, 'fractional guests': { guestCount: 1.5 },
  'wrong price': { totalPrice: 1 }, 'fractional price': { totalPrice: 3000.5 },
  'forged owner': { ownerId: 'other' }, 'forged entrant': { userId: 'other' },
  'unoffered campsite': { campsiteId: 'foreign' }, 'confirmed status': { status: 'confirmed' },
  'pending cancellation': { cancellationRequested: true }, 'extra field': { privileged: true },
  'empty stay': { endDate: Timestamp.fromMillis(tomorrow) },
  'partial night': { startDate: Timestamp.fromMillis(tomorrow + 1) },
  'submillisecond night': { startDate: new Timestamp(tomorrow / 1000, 1000) },
  'past stay': { startDate: Timestamp.fromMillis(tomorrow - 3 * day), endDate: Timestamp.fromMillis(tomorrow - day) },
  'overlong stay': { endDate: Timestamp.fromMillis(tomorrow + 367 * day), totalPrice: 550500 },
  'forged creation time': { createdAt: Timestamp.fromMillis(0) },
})) {
  test(`acceptance rejects ${name}`, async () => { await assertFails(accept(await fixture(), changes)); });
}
for (const [name, options] of Object.entries({ expired: { expires: Date.now() - 1000 },
  'before booking opens': { bookingStart: Date.now() + day }, inactive: { camp: 'inactive' },
  'foreign-owned': { camp: 'foreign' }, disabled: { uid: 'disabled' },
})) {
  test(`acceptance rejects ${name} offers or campsites`, async () => { await assertFails(accept(await fixture(options))); });
}
test('pending requests are immutable and cannot be cancelled; confirmed bookings may request cancellation before checkout', async () => {
  const f = await fixture();
  await assertSucceeds(accept(f));
  const ref = doc(client(), `reservations/${f.id}`);
  await assertFails(updateDoc(ref, { cancellationRequested: true, updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(ref, { guestCount: 3, updatedAt: serverTimestamp() }));
  await env.withSecurityRulesDisabled(c => updateDoc(doc(c.firestore(), `reservations/${f.id}`), { status: 'confirmed' }));
  await assertSucceeds(updateDoc(ref, { cancellationRequested: true, updatedAt: serverTimestamp() }));
  const past = await fixture();
  await seed(`reservations/${past.id}`, { ...past.request, status: 'confirmed', endDate: Timestamp.fromMillis(0) });
  await assertFails(updateDoc(doc(client(), `reservations/${past.id}`), { cancellationRequested: true, updatedAt: serverTimestamp() }));
});
test('worker-only retry releases an offer for a new explicit attempt, without consuming it', async () => {
  const f = await fixture();
  await assertSucceeds(accept(f));
  await env.withSecurityRulesDisabled(async c => {
    const batch = writeBatch(c.firestore());
    batch.update(doc(c.firestore(), `reservations/${f.id}`), { status: 'rejected' });
    batch.update(doc(c.firestore(), `offers/${f.offerId}`), { response: null, reservationId: null });
    await batch.commit();
  });
  await assertSucceeds(accept(f, {}, { id: `${f.id}-retry` }));
});
test('decline stays entrant-only and replacement markers remain worker-only', async () => {
  const f = await fixture();
  const response = { response: 'decline', respondedAt: serverTimestamp(), updatedAt: serverTimestamp() };
  await assertFails(updateDoc(doc(client('owner'), `offers/${f.offerId}`), response));
  await assertSucceeds(updateDoc(doc(client(), `offers/${f.offerId}`), response));
  await assertFails(accept(f));
  await assertFails(setDoc(doc(client('owner'), `offerReplacements/${f.offerId}`), { lotteryId: f.request.lotteryId }));
});
