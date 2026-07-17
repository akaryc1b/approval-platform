const PREFIX = '/api/approval-connector/v1';
const SENSITIVE_OPTION = /(authorization|credential|password|secret|signature|token)/iu;

export async function executeCommand(client, command, options, environment = process.env) {
  switch (command) {
    case 'auth':
      return authenticate(client, environment);
    case 'user':
      return findUser(client, requiredOption(options, 'user-id'));
    case 'search':
      return searchUsers(client, options);
    case 'manager-chain':
      return managerChain(
        client,
        requiredOption(options, 'user-id'),
        positiveInteger(options.levels ?? '10', 'levels', 100),
      );
    case 'suite':
      return runSuite(client, options, environment);
    default:
      throw new TypeError(`Unknown command: ${command}.`);
  }
}

async function authenticate(client, environment) {
  const credential = requiredEnvironment(environment.APPROVAL_CREDENTIAL, 'APPROVAL_CREDENTIAL');
  return client.call({
    path: `${PREFIX}/authenticate`,
    operation: 'authentication.authenticate.v1',
    body: {
      attributes: { client: 'connector-smoke' },
      credential,
      credentialType: environment.APPROVAL_CREDENTIAL_TYPE ?? 'SA_TOKEN',
    },
  });
}

async function findUser(client, userId) {
  return client.call({
    path: `${PREFIX}/organization/users/find`,
    operation: 'organization.users.find.v1',
    body: { id: externalId(client.source, 'user', userId) },
  });
}

async function searchUsers(client, options) {
  const query = {};
  if (options.keyword) {
    query.keyword = options.keyword;
  }
  if (options['department-id']) {
    query.departmentId = externalId(client.source, 'department', options['department-id']);
  }
  if (options.role) {
    query.roleCode = options.role;
  }
  if (options.position) {
    query.positionCode = options.position;
  }
  if (options.active !== undefined) {
    query.active = booleanValue(options.active, 'active');
  }
  return client.call({
    path: `${PREFIX}/organization/users/search`,
    operation: 'organization.users.search.v1',
    body: {
      page: {
        cursor: null,
        page: nonNegativeInteger(options.page ?? '0', 'page'),
        size: positiveInteger(options.size ?? '20', 'size', 500),
      },
      query,
    },
  });
}

async function managerChain(client, userId, maximumLevels) {
  return client.call({
    path: `${PREFIX}/organization/users/manager-chain`,
    operation: 'organization.users.manager-chain.v1',
    body: {
      maximumLevels,
      userId: externalId(client.source, 'user', userId),
    },
  });
}

async function runSuite(client, options, environment) {
  const authentication = await authenticate(client, environment);
  const principal = authentication?.data?.principal;
  const userId = options['user-id'] ?? principal?.id?.value;
  if (typeof userId !== 'string' || userId.length === 0) {
    throw new TypeError('Authentication response does not contain principal.id.value.');
  }
  const user = await findUser(client, userId);
  const search = await searchUsers(client, {
    keyword: principal?.username,
    page: '0',
    size: options.size ?? '10',
  });
  const managers = await managerChain(
    client,
    userId,
    positiveInteger(options.levels ?? '10', 'levels', 100),
  );
  return { authentication, managers, search, user };
}

export function parseArguments(arguments_) {
  const [command = 'help', ...tokens] = arguments_;
  const options = {};
  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    if (!token.startsWith('--')) {
      throw new TypeError(`Unexpected positional argument: ${token}.`);
    }
    const key = token.slice(2);
    if (key.length === 0) {
      throw new TypeError('Option name must not be empty.');
    }
    if (SENSITIVE_OPTION.test(key)) {
      throw new TypeError(`Sensitive option --${key} must be supplied through the environment.`);
    }
    const value = tokens[index + 1];
    if (value === undefined || value.startsWith('--')) {
      throw new TypeError(`Option --${key} requires a value.`);
    }
    options[key] = value;
    index += 1;
  }
  return { command, options };
}

function externalId(source, objectType, value) {
  return {
    objectType,
    source,
    value: String(value),
  };
}

function requiredOption(options, name) {
  const value = options[name];
  if (typeof value !== 'string' || value.length === 0) {
    throw new TypeError(`--${name} is required.`);
  }
  return value;
}

function requiredEnvironment(value, name) {
  if (typeof value !== 'string' || value.length === 0) {
    throw new TypeError(`${name} must be set in the environment.`);
  }
  return value;
}

function booleanValue(value, name) {
  if (value === 'true') {
    return true;
  }
  if (value === 'false') {
    return false;
  }
  throw new TypeError(`--${name} must be true or false.`);
}

function nonNegativeInteger(value, name) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isSafeInteger(parsed) || parsed < 0 || String(parsed) !== String(value)) {
    throw new TypeError(`--${name} must be a non-negative integer.`);
  }
  return parsed;
}

function positiveInteger(value, name, maximum) {
  const parsed = Number.parseInt(value, 10);
  if (!Number.isSafeInteger(parsed) || parsed < 1 || parsed > maximum
    || String(parsed) !== String(value)) {
    throw new TypeError(`--${name} must be between 1 and ${maximum}.`);
  }
  return parsed;
}
