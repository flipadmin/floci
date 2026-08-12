# Multi-Account Isolation

Floci supports full per-account resource isolation out of the box. Resources created by one account are invisible to all others — no configuration flag required.

## How It Works

Every incoming request carries an AWS SigV4 `Authorization` header. Floci reads the **Access Key ID** (AKID) from that header and applies one simple rule:

> **If the AKID is exactly 12 digits, it is used as the account ID.**  
> Any other key format (e.g. `AKIAIOSFODNN7EXAMPLE`) falls back to `FLOCI_DEFAULT_ACCOUNT_ID`.

```
Authorization: AWS4-HMAC-SHA256 Credential=111111111111/20260510/us-east-1/sqs/aws4_request, ...
                                            ^^^^^^^^^^^^
                                            12-digit AKID → account ID 111111111111
```

Once the account ID is determined, every storage read and write is transparently namespaced under it. An SQS queue named `orders` created by account `111111111111` is stored and retrieved as `111111111111/orders` — completely separate from the same queue name under account `222222222222`.

!!! note "Same convention as LocalStack"
    This 12-digit AKID → account ID rule matches LocalStack's multi-account behavior, so existing multi-account test setups work without changes.

## Temporary Credentials (AssumeRole)

STS temporary credentials are also routed to the correct account. When you call `AssumeRole` (or `AssumeRoleWithWebIdentity`, `AssumeRoleWithSAML`, `GetFederationToken`), Floci issues a temporary `ASIA…` access key and remembers which account it belongs to:

- **`AssumeRole` / web-identity / SAML / federation** → the account in the role (or federated-user) ARN. Assuming `arn:aws:iam::222222222222:role/Deployer` from account `111111111111` yields credentials that resolve to **`222222222222`**, so resources created with them land in account B's namespace.
- **`GetSessionToken`** → the caller's account (these credentials carry no role), so they stay in the same account.

```
Account 111111111111 ──AssumeRole arn:aws:iam::222222222222:role/Deployer──▶ ASIA… temp key
                                                                              │
ASIA… temp key ──CreateTable orders──▶ stored as 222222222222/...::orders  ◀──┘
```

This makes the cross-account `AssumeRole`-then-provision pattern (e.g. CloudFormation deploying into a target account) work locally exactly as it does in AWS. Resolution precedence is: **12-digit AKID → account ID; otherwise temporary-session lookup → `FLOCI_DEFAULT_ACCOUNT_ID`.**

## Default Behavior (Single Account)

If you use any non-12-digit credentials (e.g. `test`, `AKIA…`), all requests resolve to the default account ID:

```bash
FLOCI_DEFAULT_ACCOUNT_ID=000000000000   # default
```

All ARNs and URLs use this value:

```
arn:aws:sqs:us-east-1:000000000000:my-queue
http://localhost:4566/000000000000/my-queue
```

You can change the default account ID without enabling per-request isolation:

```bash
FLOCI_DEFAULT_ACCOUNT_ID=123456789012
```

## Enabling Multi-Account Isolation

Use 12-digit numeric access key IDs. The secret access key can be any non-empty string — Floci does not validate signatures by default.

### AWS CLI

```bash
# Configure two named profiles
aws configure --profile account-a
# AWS Access Key ID: 111111111111
# AWS Secret Access Key: test

aws configure --profile account-b
# AWS Access Key ID: 222222222222
# AWS Secret Access Key: test

export AWS_ENDPOINT_URL=http://localhost:4566

# Create the same queue name under both accounts
aws sqs create-queue --queue-name orders --profile account-a
aws sqs create-queue --queue-name orders --profile account-b

# Each account sees only its own queue
aws sqs list-queues --profile account-a   # → .../111111111111/orders
aws sqs list-queues --profile account-b   # → .../222222222222/orders
```

### AWS SDK (Java)

```java
StaticCredentialsProvider accountA = StaticCredentialsProvider.create(
    AwsBasicCredentials.create("111111111111", "test"));

StaticCredentialsProvider accountB = StaticCredentialsProvider.create(
    AwsBasicCredentials.create("222222222222", "test"));

SqsClient clientA = SqsClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(accountA)
    .build();

SqsClient clientB = SqsClient.builder()
    .endpointOverride(URI.create("http://localhost:4566"))
    .region(Region.US_EAST_1)
    .credentialsProvider(accountB)
    .build();

// Both calls succeed, resources are fully isolated
clientA.createQueue(r -> r.queueName("orders"));
clientB.createQueue(r -> r.queueName("orders"));
```

### AWS SDK (Python)

```python
import boto3

def client(service, account_id):
    return boto3.client(
        service,
        endpoint_url="http://localhost:4566",
        region_name="us-east-1",
        aws_access_key_id=account_id,      # 12-digit → account ID
        aws_secret_access_key="test",
    )

sqs_a = client("sqs", "111111111111")
sqs_b = client("sqs", "222222222222")

sqs_a.create_queue(QueueName="orders")
sqs_b.create_queue(QueueName="orders")

print(sqs_a.list_queues()["QueueUrls"])  # [".../111111111111/orders"]
print(sqs_b.list_queues()["QueueUrls"])  # [".../222222222222/orders"]
```

## ARNs Include the Correct Account ID

Floci embeds the resolved account ID in every ARN it generates:

```
arn:aws:sqs:us-east-1:111111111111:orders
arn:aws:lambda:us-east-1:222222222222:function:my-fn
arn:aws:s3:::my-bucket                         # S3 ARNs are account-agnostic
```

## Isolation Scope

All services that use `StorageFactory` participate in account isolation automatically. This covers every service in Floci — SQS, SNS, S3, DynamoDB, Lambda, SSM, Secrets Manager, KMS, Kinesis, EventBridge, Cognito, RDS, ElastiCache, OpenSearch, MSK, and more.

Background workers (Lambda event-source pollers, DynamoDB TTL sweeper, MSK readiness poller, OpenSearch readiness poller) iterate across all accounts internally and route writes back to the originating account. No cross-account data leaks through these async paths.

## Account Clone & Clear (Test Isolation)

Beyond preventing cross-account collisions, Floci can copy one account's data into another, or wipe an account clean, on demand. This is aimed at test suites that want a fresh, isolated account per test case — cloned from a shared baseline instead of provisioning resources from scratch every time — then discarded when the test finishes.

This is an admin operation, not part of any AWS API emulation, so it lives on Floci's own `/_floci` (or `/_localstack`) prefix rather than a service endpoint:

```
POST /_floci/accounts/{targetAccountId}/clone
{ "source": "111111111111", "services": ["dynamodb", "s3"] }

POST /_floci/accounts/{targetAccountId}/clear
{ "services": ["dynamodb", "s3"] }
```

- `services` is always required and explicit for both endpoints — there is no `"all"` shortcut, so a call only ever touches the services it names. An unknown or unsupported service name fails the whole call before anything is touched, never a partial clone/clear.
- `clone` replaces the target's data for each requested service outright, not a merge — calling `clone` again on the same target simply resets it to match the source. Cloning is optimized for speed rather than mid-flight consistency: the source account is expected to stay frozen and the target unwritten for the duration of the call.
- `clear` on a service the account has no data in is a no-op.

!!! note "Supported services"
    Currently: `dynamodb`, `s3`, `sqs`, `sns`, `ssm`. Every service that keeps its state entirely behind `StorageFactory` gets clone/clear for free; a handful of services still keep some state outside that layer and aren't wired up yet.

Example: give each parallel test worker its own throwaway account cloned from a shared baseline, then wipe it when the test finishes:

```bash
BASELINE=111111111111
WORKER=$(uuidgen | tr -dc '0-9' | cut -c1-12)   # any fresh 12-digit id

curl -X POST http://localhost:4566/_floci/accounts/$WORKER/clone \
  -H 'Content-Type: application/json' \
  -d "{\"source\": \"$BASELINE\", \"services\": [\"dynamodb\", \"s3\", \"sqs\", \"sns\", \"ssm\"]}"

# ... run the test against $WORKER ...

curl -X POST http://localhost:4566/_floci/accounts/$WORKER/clear \
  -H 'Content-Type: application/json' \
  -d '{"services": ["dynamodb", "s3", "sqs", "sns", "ssm"]}'
```

## Signature Validation

By default Floci **does not** validate SigV4 signatures — only the access key ID matters for account resolution. The secret access key can be any non-empty string.

To enforce real signature validation:

```bash
FLOCI_AUTH_VALIDATE_SIGNATURES=true
FLOCI_AUTH_PRESIGN_SECRET=your-secret   # for pre-signed URL verification
```

When `validate-signatures` is `false` (the default), account isolation still works correctly — the AKID is extracted from the `Authorization` header regardless of whether the signature itself is verified.

## Persistence and Account Isolation

Storage keys are namespaced per account at the persistence layer. When using `persistent`, `hybrid`, or `wal` storage modes, each account's data is stored under its own key prefix. Restarting Floci restores each account's resources independently.

## Configuration Reference

| Variable | Default | Description |
|---|---|---|
| `FLOCI_DEFAULT_ACCOUNT_ID` | `000000000000` | Account ID used when the AKID is not exactly 12 digits |
| `FLOCI_DEFAULT_REGION` | `us-east-1` | Region used when not derivable from the `Authorization` header |
| `FLOCI_AUTH_VALIDATE_SIGNATURES` | `false` | Enforce SigV4 signature verification |
