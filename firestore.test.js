const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_docvault_123";
const BOB_UID = "bob_docvault_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

function pastDate(msAgo = 2000) {
  return new Date(Date.now() - msAgo);
}

function futureDate(msAhead = 60000) {
  return new Date(Date.now() + msAhead);
}

function validUserProfile(uid) {
  const now = pastDate();
  return {
    userId: uid,
    displayName: "Alice Archivist",
    email: "alice@example.com",
    e2eeEnabled: false,
    storageUsedBytes: 0,
    createdAt: now,
    updatedAt: now,
  };
}

function validFolder(uid, privateVault = false) {
  const now = pastDate();
  return {
    userId: uid,
    name: privateVault ? "Private Tax Vault" : "Receipts 2026",
    colorHex: "#0D5C63",
    iconName: privateVault ? "lock" : "receipt",
    privateVault,
    tags: ["finance", "2026"],
    createdAt: now,
    updatedAt: now,
  };
}

function validDocument(uid) {
  const now = pastDate();
  return {
    userId: uid,
    title: "Flight Receipt SFO-NYC",
    fileName: "flight_receipt.jpg",
    mimeType: "image/jpeg",
    fileCategory: "IMAGE",
    fileSizeBytes: 245120,
    uploadDateMillis: Date.now() - 86400000,
    folderId: "folder_1",
    aiCategory: "IDs & Travel",
    tags: ["flight", "travel"],
    extractedText: "Confirmation ABC123 Flight UA401",
    summary: "United Airlines confirmation ABC123.",
    thumbnailBase64: "iVBORw0KGgo=",
    hasCloudBlob: true,
    encrypted: false,
    encryptedPayload: "",
    encryptionIv: "",
    ocrStatus: "COMPLETED",
    pageCount: 1,
    createdAt: now,
    updatedAt: now,
  };
}

function validBlob(uid, docId) {
  const now = pastDate();
  return {
    userId: uid,
    docId,
    mimeType: "image/jpeg",
    dataBase64: "AQIDBA==",
    encrypted: false,
    encryptionIv: "",
    byteLength: 4,
    createdAt: now,
    updatedAt: now,
  };
}

function validWebsite(uid) {
  const now = pastDate();
  return {
    userId: uid,
    name: "FedEx Tracking",
    urlTemplate: "https://www.fedex.com/fedextrack/?trknbr={query}",
    category: "Tracking",
    usageCount: 0,
    createdAt: now,
    updatedAt: now,
  };
}

function validHistory(uid) {
  return {
    userId: uid,
    query: "What is my flight confirmation number?",
    mode: "AI_ASK",
    aiAnswerPreview: "Your flight confirmation is ABC123.",
    resultCount: 1,
    createdAt: pastDate(),
  };
}

// --- 1. Unauthenticated Denials ---

test("Unauthenticated user cannot read or write any collection", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  await assertFails(unauthDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).set(validUserProfile(ALICE_UID))
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("documents").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("folders").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("saved_websites").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("search_history").get()
  );
});

// --- 2. User Profile Ownership, Updates & Ghost Field Rejection ---

test("Owner can create and update UserProfile; cross-user and ghost fields fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).set(validUserProfile(ALICE_UID))
  );

  // Update E2EE settings
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      e2eeEnabled: true,
      e2eeSalt: "c2FsdA==",
      e2eeVerifier: "dmVyaWZpZXI=",
      updatedAt: pastDate(500),
    })
  );

  // Update storage usage
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).update({
      storageUsedBytes: 512000,
      updatedAt: pastDate(200),
    })
  );

  // Ghost field shadow update fails
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      isAdmin: true,
      updatedAt: pastDate(100),
    })
  );

  // Cross-user read/write fails
  await assertFails(bobDb.collection("users").doc(ALICE_UID).get());
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).update({
      displayName: "Hacked",
      updatedAt: pastDate(100),
    })
  );

  // Future timestamp rejected
  await assertFails(
    aliceDb.collection("users").doc(ALICE_UID).update({
      storageUsedBytes: 100,
      updatedAt: futureDate(),
    })
  );
});

// --- 3. Folders CRUD & Cross-User Isolation ---

test("Owner can CRUD custom and private E2EE folders; cross-user fails", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();
  const folderRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("folders")
    .doc("folder_1");

  await assertSucceeds(folderRef.set(validFolder(ALICE_UID, true)));
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("folders").get()
  );
  await assertSucceeds(
    folderRef.update({
      name: "Private Legal Vault",
      tags: ["legal", "tax"],
      updatedAt: pastDate(500),
    })
  );

  // Reject immutable createdAt modification
  await assertFails(
    folderRef.update({
      createdAt: pastDate(100),
      updatedAt: pastDate(100),
    })
  );

  // Reject Bob reading or writing Alice's folders
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("folders").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("folders").doc("folder_1").delete()
  );

  await assertSucceeds(folderRef.delete());
});

// --- 4. Documents, Editable Upload Date, Blobs & @DocumentId Enforcement ---

test("Owner can CRUD documents and off-device blobs; invalid enum, id key, and cross-user fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const bobDb = testEnv.authenticatedContext(BOB_UID).firestore();

  const docRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("documents")
    .doc("doc_1");
  const blobRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("doc_blobs")
    .doc("doc_1");

  await assertSucceeds(docRef.set(validDocument(ALICE_UID)));
  await assertSucceeds(blobRef.set(validBlob(ALICE_UID, "doc_1")));

  // Edit upload date action
  await assertSucceeds(
    docRef.update({
      uploadDateMillis: Date.now() - 172800000,
      updatedAt: pastDate(500),
    })
  );

  // Edit metadata / AI category / tags / E2EE action
  await assertSucceeds(
    docRef.update({
      title: "Updated Flight Receipt",
      aiCategory: "Receipts & Finance",
      tags: ["flight", "expense"],
      updatedAt: pastDate(300),
    })
  );

  // List subcollection succeeds for owner
  await assertSucceeds(
    aliceDb.collection("users").doc(ALICE_UID).collection("documents").get()
  );

  // Reject extra @DocumentId 'id' field on create
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("documents")
      .doc("doc_bad_id")
      .set({ ...validDocument(ALICE_UID), id: "doc_bad_id" })
  );

  // Reject invalid fileCategory enum
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("documents")
      .doc("doc_bad_enum")
      .set({ ...validDocument(ALICE_UID), fileCategory: "EXECUTABLE" })
  );

  // Reject mismatched blob docId
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("doc_blobs")
      .doc("doc_mismatch")
      .set(validBlob(ALICE_UID, "doc_other"))
  );

  // Reject cross-user access
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("documents").doc("doc_1").get()
  );
  await assertFails(
    bobDb.collection("users").doc(ALICE_UID).collection("doc_blobs").doc("doc_1").get()
  );
});

// --- 5. Saved Websites URL Scheme Validation & Search History ---

test("Saved websites enforce HTTP/HTTPS scheme and search history is append/delete only", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const sitesCol = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("saved_websites");
  const historyCol = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("search_history");

  await assertSucceeds(sitesCol.doc("site_1").set(validWebsite(ALICE_UID)));
  await assertSucceeds(
    sitesCol.doc("site_1").update({
      usageCount: 1,
      updatedAt: pastDate(400),
    })
  );

  // Reject dangerous javascript: URL scheme
  await assertFails(
    sitesCol.doc("site_xss").set({
      ...validWebsite(ALICE_UID),
      urlTemplate: "javascript:alert(1)",
    })
  );

  // Search history create, list, delete succeed; update fails
  await assertSucceeds(historyCol.doc("hist_1").set(validHistory(ALICE_UID)));
  await assertSucceeds(historyCol.get());
  await assertFails(
    historyCol.doc("hist_1").update({ query: "tampered" })
  );
  await assertSucceeds(historyCol.doc("hist_1").delete());
});
