# PlantArena API — Bruno collection

This folder contains 54 distinct API operations and 89 request files, including role-specific copies where the API has different access rules.

## Setup

1. Open this folder as a Bruno collection and select the Local environment.
2. Set the role ID variables to UUIDs of existing active users in your dev/test database. X-Demo-User-Id is not a token; the server reads the user's roles from the database.
3. Run Guest sessions / Create guest session, copy token from its response into guestToken, then use Guest variants under Feed and Voting. Guest votes are limited to global windows.
4. Fill IDs such as tournamentId, plantId, fileId, tagId, windowId, entryId, clusterId, and invitationId from responses before sending dependent requests.
5. Provide imageFilePath for Files / Upload image. Use a JPEG/PNG file under the 10 MiB and 20-megapixel limits.

Variables that change between requests (userUserId, imageFilePath, newUserEmail, plantId, entryId, and others) are defined as multiple commented copies — "slots": one enabled, the rest disabled. Before each request, enable the slot matching the current actor and fill its value from the previous response.

## Demo

Step-by-step demo (admin → moderator → 3 users → private tournament → winner) with the exact slot switches per request: [`../docs/demo/bruno-demo.md`](../docs/demo/bruno-demo.md).

The current API has no Bearer/JWT auth. User identity is supplied through X-Demo-User-Id only in dev/test. Internal demo jobs exist only in dev/test.

Optional query parameters remain visible and disabled in request definitions. The tournament status filter supports DRAFT, REGISTRATION_OPEN, RUNNING, FINISHED, and CANCELLED. Global final leaderboard scope is FINAL.
