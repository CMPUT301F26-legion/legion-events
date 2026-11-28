// Regression checks for the group's existing permissions alongside local lottery draws.
import { readFileSync } from 'node:fs';
import { after, before, test } from 'node:test';
import assert from 'node:assert/strict';
import { initializeTestEnvironment, assertFails, assertSucceeds } from '@firebase/rules-unit-testing';
import { doc, getDoc, setDoc, updateDoc, deleteDoc, writeBatch, serverTimestamp, Timestamp, GeoPoint, setLogLevel } from 'firebase/firestore';

const endpoint = process.env.FIRESTORE_EMULATOR_HOST;
assert.ok(endpoint, 'Set FIRESTORE_EMULATOR_HOST to run the rules tests.');
const [host, port] = endpoint.split(':');
assert.ok(['127.0.0.1', 'localhost'].includes(host), 'Use a local emulator.');
const rules = readFileSync(process.env.LOCAL_DRAW_RULES_PATH || new URL('../../firestore.rules', import.meta.url), 'utf8');
let environment;
setLogLevel('silent');

/** Supplies the complete profile schema accepted by the group's existing rules. */
function profile(role = 'user') {
  return { name: 'Test account', email: 'test@example.com', phone: '', role, photoId: null,
    notificationsEnabled: true, fcmToken: null, disabled: false,
    createdAt: serverTimestamp(), updatedAt: serverTimestamp() };
}

/** Supplies an owner campsite with every required field and valid shared limits. */
function campsite(active = true) {
  return { ownerId: 'owner', name: 'Test campsite', description: '', locationName: 'Test location',
    location: new GeoPoint(53, -113), maxGuests: 4, pricePerNight: 1000, amenities: [], tags: [],
    rules: '', cancellationPolicy: '', availableNights: [], photoId: null, ratingAverage: 0,
    ratingCount: 0, active, createdAt: serverTimestamp(), updatedAt: serverTimestamp() };
}

/** Supplies future, registration-open, or booking-open dates without changing the lottery schema. */
function lottery(phase = 'future') {
  const now = Date.now();
  return { ownerId: 'owner', campsiteIds: ['camp'],
    registrationStartAt: Timestamp.fromMillis(now + (phase === 'future' ? 60_000 : -60_000)),
    registrationEndAt: Timestamp.fromMillis(now + (phase === 'booking' ? -5_000 : 600_000)),
    bookingStartAt: Timestamp.fromMillis(now + (phase === 'booking' ? -1_000 : 1_200_000)),
    bookingEndAt: Timestamp.fromMillis(now + 86_400_000), winnerCount: 1, waitlistLimit: 100,
    entrantCount: 0, status: 'open', winnersDrawn: phase === 'booking',
    createdAt: serverTimestamp(), updatedAt: serverTimestamp() };
}

/** Creates synthetic fixtures without granting any additional authority to the tested clients. */
async function seed(path, data) {
  await environment.withSecurityRulesDisabled(context => setDoc(doc(context.firestore(), path), data));
}

/** Creates an authenticated SDK client subject to all deployed rule checks. */
function client(uid = 'owner') { return environment.authenticatedContext(uid).firestore(); }

before(async () => {
  environment = await initializeTestEnvironment({
    projectId: `demo-existing-permissions-${process.pid}`,
    firestore: { host, port: Number(port), rules },
  });
  for (const uid of ['owner', 'entrant', 'other', 'admin']) await seed(`users/${uid}`, profile(uid === 'admin' ? 'admin' : 'user'));
  await seed('campsites/camp', campsite());
});

after(async () => { if (environment) await environment.cleanup(); });

test('profile creation, editing, token updates, privacy, and role protection are preserved', async () => {
  const db = client('new-user');
  await assertSucceeds(setDoc(doc(db, 'users/new-user'), profile()));
  await assertSucceeds(getDoc(doc(db, 'users/new-user')));
  await assertSucceeds(updateDoc(doc(db, 'users/new-user'), {
    name: 'Updated name', fcmToken: 'test-token', notificationsEnabled: false, updatedAt: serverTimestamp(),
  }));
  await assertFails(updateDoc(doc(db, 'users/new-user'), { role: 'admin', updatedAt: serverTimestamp() }));
  await assertFails(getDoc(doc(client('other'), 'users/new-user')));
  await assertSucceeds(getDoc(doc(client('admin'), 'users/new-user')));
  await assertSucceeds(deleteDoc(doc(db, 'users/new-user')));
});

test('campsite owner writes, private reads, public reads, and rating protection are preserved', async () => {
  const reference = doc(client(), 'campsites/private');
  await assertSucceeds(setDoc(reference, campsite(false)));
  await assertSucceeds(getDoc(reference));
  await assertFails(getDoc(doc(client('other'), 'campsites/private')));
  await assertFails(updateDoc(doc(client('other'), 'campsites/private'), { name: 'Taken over', updatedAt: serverTimestamp() }));
  await assertSucceeds(updateDoc(reference, { name: 'Owner edit', active: true, updatedAt: serverTimestamp() }));
  await assertSucceeds(getDoc(doc(client('other'), 'campsites/private')));
  await assertFails(updateDoc(reference, { ratingAverage: 5, updatedAt: serverTimestamp() }));
  await assertFails(deleteDoc(reference));
});

test('lottery creation and pre-registration edits still require ownership of the campsites', async () => {
  const data = lottery();
  await assertSucceeds(setDoc(doc(client(), 'lotteries/setup'), data));
  await assertSucceeds(updateDoc(doc(client(), 'lotteries/setup'), { winnerCount: 2, updatedAt: serverTimestamp() }));
  await assertFails(setDoc(doc(client('other'), 'lotteries/wrong-campsite'), { ...data, ownerId: 'other' }));
  await assertFails(updateDoc(doc(client('other'), 'lotteries/setup'), { winnerCount: 3, updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(doc(client(), 'lotteries/setup'), { winnersDrawn: true, updatedAt: serverTimestamp() }));
});

test('joining, withdrawing, and rejoining keep the entrant counter atomic and prevent late owner edits', async () => {
  await seed('lotteries/entries', lottery('open'));
  const db = client('entrant');
  const entry = doc(db, 'waitlist/entries_entrant');
  const reference = doc(db, 'lotteries/entries');
  const join = writeBatch(db);
  join.set(entry, { lotteryId: 'entries', userId: 'entrant', status: 'joined', joinedAt: serverTimestamp(), updatedAt: serverTimestamp() });
  join.update(reference, { entrantCount: 1, updatedAt: serverTimestamp() });
  await assertSucceeds(join.commit());
  await assertSucceeds(getDoc(doc(client(), 'waitlist/entries_entrant')));
  await assertFails(getDoc(doc(client('other'), 'waitlist/entries_entrant')));
  await assertFails(updateDoc(reference, { entrantCount: 2, updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(doc(client(), 'lotteries/entries'), {
    registrationEndAt: Timestamp.fromMillis(Date.now() - 1_000), updatedAt: serverTimestamp(),
  }));
  const leave = writeBatch(db);
  leave.update(entry, { status: 'withdrawn', updatedAt: serverTimestamp() });
  leave.update(reference, { entrantCount: 0, updatedAt: serverTimestamp() });
  await assertSucceeds(leave.commit());
  const rejoin = writeBatch(db);
  rejoin.update(entry, { status: 'joined', joinedAt: serverTimestamp(), updatedAt: serverTimestamp() });
  rejoin.update(reference, { entrantCount: 1, updatedAt: serverTimestamp() });
  await assertSucceeds(rejoin.commit());
});

test('only the offered entrant can decline a pending offer once', async () => {
  await seed('lotteries/decline', lottery('booking'));
  await seed('offers/decline_entrant', { lotteryId: 'decline', userId: 'entrant', campsiteIds: ['camp'],
    status: 'pending', response: null, expiresAt: Timestamp.fromMillis(Date.now() + 600_000) });
  const response = { response: 'decline', respondedAt: serverTimestamp(), updatedAt: serverTimestamp() };
  await assertFails(updateDoc(doc(client(), 'offers/decline_entrant'), response));
  await assertSucceeds(updateDoc(doc(client('entrant'), 'offers/decline_entrant'), response));
  await assertFails(updateDoc(doc(client('entrant'), 'offers/decline_entrant'), response));
});

test('reservation requests require entrant acceptance and only the worker may confirm', async () => {
  const dates = lottery('booking');
  await seed('lotteries/booking', dates);
  await seed('offers/booking_entrant', { lotteryId: 'booking', userId: 'entrant', campsiteIds: ['camp'],
    status: 'pending', response: null, expiresAt: dates.bookingEndAt });
  const db = client('entrant');
  const reference = doc(db, 'reservations/booking');
  const batch = writeBatch(db);
  batch.set(reference, { lotteryId: 'booking', offerId: 'booking_entrant', campsiteId: 'camp',
    userId: 'entrant', ownerId: 'owner', startDate: Timestamp.fromMillis((Math.floor(Date.now() / 86_400_000) + 1) * 86_400_000),
    endDate: Timestamp.fromMillis((Math.floor(Date.now() / 86_400_000) + 2) * 86_400_000), guestCount: 2, totalPrice: 1000,
    rulesAccepted: true, status: 'requested', cancellationRequested: false,
    createdAt: serverTimestamp(), updatedAt: serverTimestamp() });
  batch.update(doc(db, 'offers/booking_entrant'), { response: 'accept', reservationId: 'booking',
    respondedAt: serverTimestamp(), updatedAt: serverTimestamp() });
  await assertSucceeds(batch.commit());
  await assertFails(getDoc(doc(client('other'), 'reservations/booking')));
  await assertFails(updateDoc(doc(client(), 'reservations/booking'), { status: 'confirmed', updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(reference, { cancellationRequested: true, updatedAt: serverTimestamp() }));
  await environment.withSecurityRulesDisabled(context => updateDoc(doc(context.firestore(), 'reservations/booking'), { status: 'confirmed' }));
  await assertFails(updateDoc(reference, { status: 'cancelled', updatedAt: serverTimestamp() }));
  await assertSucceeds(updateDoc(reference, { cancellationRequested: true, updatedAt: serverTimestamp() }));
  await assertSucceeds(updateDoc(doc(client(), 'reservations/booking'), { status: 'rejected', updatedAt: serverTimestamp() }));
  await assertFails(setDoc(doc(db, 'bookedNights/client-write'), { reservationId: 'booking' }));
});

test('notification recipients can read and mark existing Inbox notices without changing their contents', async () => {
  await seed('notifications/existing', { toUserId: 'entrant', type: 'lottery_lost', lotteryId: 'existing', offerId: null, createdAt: serverTimestamp() });
  await assertSucceeds(getDoc(doc(client('entrant'), 'notifications/existing')));
  await assertSucceeds(updateDoc(doc(client('entrant'), 'notifications/existing'), { read: true, updatedAt: serverTimestamp() }));
  await assertFails(getDoc(doc(client('other'), 'notifications/existing')));
  await assertFails(updateDoc(doc(client('entrant'), 'notifications/existing'), { toUserId: 'other', updatedAt: serverTimestamp() }));
});

test('entrants can start lottery-linked conversations with the campsite owner and exchange messages', async () => {
  await seed('lotteries/chat', lottery('open'));
  const db = client('entrant');
  await assertSucceeds(setDoc(doc(db, 'conversations/chat'), { campsiteId: 'camp', lotteryId: 'chat', reservationId: null,
    entrantId: 'entrant', ownerId: 'owner', lastMessage: '', createdAt: serverTimestamp(), updatedAt: serverTimestamp() }));
  await assertSucceeds(getDoc(doc(client(), 'conversations/chat')));
  await assertFails(getDoc(doc(client('other'), 'conversations/chat')));
  await assertFails(updateDoc(doc(db, 'conversations/chat'), { ownerId: 'other', updatedAt: serverTimestamp() }));
  await assertSucceeds(updateDoc(doc(db, 'conversations/chat'), { lastMessage: 'Hello', updatedAt: serverTimestamp() }));
  await assertSucceeds(setDoc(doc(db, 'messages/chat'), { conversationId: 'chat', senderId: 'entrant', receiverId: 'owner',
    text: 'Hello', read: false, createdAt: serverTimestamp(), updatedAt: serverTimestamp() }));
  await assertFails(getDoc(doc(client('other'), 'messages/chat')));
  await assertSucceeds(updateDoc(doc(client(), 'messages/chat'), { read: true, updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(doc(db, 'messages/chat'), { read: true, updatedAt: serverTimestamp() }));
});

test('photo ownership and per-user campsite rating permissions are preserved', async () => {
  const image = { ownerId: 'owner', targetType: 'campsite', targetId: 'camp', imageBase64: 'test',
    mimeType: 'image/png', createdAt: serverTimestamp(), updatedAt: serverTimestamp() };
  await assertSucceeds(setDoc(doc(client(), 'photos/camp'), image));
  await assertFails(setDoc(doc(client('entrant'), 'photos/not-owned'), { ...image, ownerId: 'entrant' }));
  await assertSucceeds(setDoc(doc(client('entrant'), 'ratings/camp_entrant'), { campsiteId: 'camp', userId: 'entrant',
    score: 5, createdAt: serverTimestamp(), updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(doc(client('other'), 'ratings/camp_entrant'), { score: 1, updatedAt: serverTimestamp() }));
  await assertSucceeds(updateDoc(doc(client('entrant'), 'ratings/camp_entrant'), { score: 4, updatedAt: serverTimestamp() }));
  await assertSucceeds(deleteDoc(doc(client('entrant'), 'ratings/camp_entrant')));
});

test('owner notices remain owner-created, immutable, and private to recipients', async () => {
  await seed('lotteries/notice', lottery('open'));
  const notice = { ownerId: 'owner', lotteryId: 'notice', message: 'Lottery update', recipientIds: ['entrant'],
    createdAt: serverTimestamp(), updatedAt: serverTimestamp() };
  await assertSucceeds(setDoc(doc(client(), 'ownerNotices/notice'), notice));
  await assertSucceeds(getDoc(doc(client('entrant'), 'ownerNotices/notice')));
  await assertFails(getDoc(doc(client('other'), 'ownerNotices/notice')));
  await assertFails(setDoc(doc(client('other'), 'ownerNotices/wrong-owner'), { ...notice, ownerId: 'other' }));
  await assertFails(updateDoc(doc(client(), 'ownerNotices/notice'), { message: 'Edited', updatedAt: serverTimestamp() }));
});

test('admin moderation and disabled-account restrictions remain enforced', async () => {
  await seed('users/blocked', profile());
  await assertSucceeds(updateDoc(doc(client('admin'), 'users/blocked'), { disabled: true, moderationReason: 'Test', updatedAt: serverTimestamp() }));
  await assertFails(getDoc(doc(client('blocked'), 'campsites/camp')));
  await assertSucceeds(updateDoc(doc(client('admin'), 'campsites/private'), { active: false, moderationReason: 'Test', updatedAt: serverTimestamp() }));
  await assertFails(updateDoc(doc(client('entrant'), 'users/owner'), { disabled: true, updatedAt: serverTimestamp() }));
});

test('unauthenticated clients and unknown collections remain denied', async () => {
  const db = environment.unauthenticatedContext().firestore();
  await assertFails(getDoc(doc(db, 'campsites/camp')));
  await assertFails(setDoc(doc(db, 'users/anonymous'), profile()));
  await assertFails(setDoc(doc(client(), 'unexpected/document'), { value: 'test' }));
});
