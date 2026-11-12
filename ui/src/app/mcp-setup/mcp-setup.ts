/**
 * The pure half of the MCP setup page: what a client is told to connect to
 * this instance's MCP endpoint. Kept out of the component so the snippets can
 * be checked without rendering.
 *
 * The endpoint is served by the backend itself at `/api/sql-explorer/mcp`
 * (streamable HTTP, stateless), so a client is given a URL — no install, no
 * checkout path. The tools are not listed here: the page asks the endpoint
 * (`tools/list`), so this list cannot drift from the server's.
 */

/** The path the UI reaches the backend through (nginx / the dev proxy). */
export const API_PATH = '/api/sql-explorer';
export const MCP_PATH = `${API_PATH}/mcp`;
export const ENVIRONMENT_HEADER = 'X-Sql-Explorer-Environment';

/**
 * Where a client's config reads its access token from (sqlx-e13.5), so the
 * secret is in the environment rather than in a config file that might be
 * checked in or shared. Claude Code expands `${VAR}` in `.mcp.json` headers;
 * a shell expands `$VAR` in `claude mcp add`.
 */
export const TOKEN_ENV = 'SQL_EXPLORER_TOKEN';

/**
 * Claude Desktop goes through mcp-remote, whose documentation advises keeping
 * the whole header value, `Bearer` and all, in one variable: some clients
 * split arguments on spaces.
 */
export const DESKTOP_AUTH_ENV = 'SQL_EXPLORER_AUTH';

export interface McpConfig {
  /** The endpoint's URL as a client on this machine reaches it. */
  url: string;
  /** Sent as {@link ENVIRONMENT_HEADER}; null leaves it out, so the backend's default is used. */
  environment: string | null;
  /**
   * Whether the endpoint wants an access token: sign-in is on (`oidc`).
   * Then every snippet sends `Authorization: Bearer`, read from
   * {@link TOKEN_ENV}. Absent or false, as with sign-in off, none is sent.
   */
  token?: boolean;
}

/** The endpoint, at the origin this page was served from. */
export function mcpUrl(origin: string): string {
  return origin.replace(/\/$/, '') + MCP_PATH;
}

/** Single-quoted for a POSIX shell, unless it plainly needs no quoting. */
function shellWord(value: string): string {
  return /^[\w@%+=:,./-]+$/.test(value) ? value : `'${value.replace(/'/g, '\'\\\'\'')}'`;
}

/** `claude mcp add`, registering the endpoint with Claude Code. */
export function claudeCodeCommand(config: McpConfig): string {
  const lines = [`claude mcp add --transport http sql-explorer ${shellWord(config.url)}`];
  if (config.environment) {
    lines.push(`  --header ${shellWord(`${ENVIRONMENT_HEADER}: ${config.environment}`)}`);
  }
  if (config.token) {
    // double quotes, so the shell puts the token in from the environment
    lines.push(`  --header "Authorization: Bearer $${TOKEN_ENV}"`);
  }
  return lines.join(' \\\n');
}

/** A project's `.mcp.json`, for Claude Code. */
export function mcpJson(config: McpConfig): string {
  const server: Record<string, unknown> = {type: 'http', url: config.url};
  const headers: Record<string, string> = {};
  if (config.environment) {
    headers[ENVIRONMENT_HEADER] = config.environment;
  }
  if (config.token) {
    headers['Authorization'] = `Bearer \${${TOKEN_ENV}}`;
  }
  if (Object.keys(headers).length) {
    server['headers'] = headers;
  }
  return JSON.stringify({mcpServers: {'sql-explorer': server}}, null, 2);
}

/**
 * `claude_desktop_config.json`. Claude Desktop's config launches local
 * commands, so the URL goes through `mcp-remote`, which bridges it; the
 * header is written without a space after the colon, as mcp-remote's own
 * documentation advises for argument lists.
 */
export function desktopJson(config: McpConfig): string {
  const args = ['-y', 'mcp-remote', config.url];
  if (config.environment) {
    args.push('--header', `${ENVIRONMENT_HEADER}:${config.environment}`);
  }
  const server: Record<string, unknown> = {command: 'npx', args};
  if (config.token) {
    args.push('--header', `Authorization:\${${DESKTOP_AUTH_ENV}}`);
    server['env'] = {[DESKTOP_AUTH_ENV]: 'Bearer <paste your sqlx_ token here>'};
  }
  return JSON.stringify({mcpServers: {'sql-explorer': server}}, null, 2);
}

/** One tool as `tools/list` describes it. */
export interface McpToolInfo {
  name: string;
  description: string;
  /** False for the tag writes, true for everything that only reads. */
  readOnly: boolean;
}

interface ListedTool {
  name: string;
  description?: string;
  annotations?: {readOnlyHint?: boolean};
}

/** The tools from a `tools/list` response, in the order the server gives them. */
export function toolsOf(response: {result?: {tools?: ListedTool[]}}): McpToolInfo[] {
  return (response.result?.tools ?? []).map(tool => ({
    name: tool.name,
    description: tool.description ?? '',
    readOnly: tool.annotations?.readOnlyHint !== false
  }));
}

/** The JSON-RPC request that lists the tools; stateless, so no initialize first. */
export const TOOLS_LIST_REQUEST = {jsonrpc: '2.0', id: 1, method: 'tools/list', params: {}};
