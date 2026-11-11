import {buildStatement, builderMissing, declaredType, EMPTY_BUILDER, fromExistingColumn, quote} from './ddl-builder';

describe('ddl-builder', () => {
  const build = (change: Partial<typeof EMPTY_BUILDER>) =>
    buildStatement('app_main', 'account', {...EMPTY_BUILDER, ...change});

  it('adds a column with its definition, placed after another', () => {
    expect(build({op: 'add-column', newName: 'name_u8', type: 'VARCHAR(255)', charset: 'utf8mb4',
      collation: 'utf8mb4_0900_ai_ci', nullable: true, after: 'name'}))
      .toBe('ALTER TABLE `app_main`.`account`\n  ADD COLUMN `name_u8` VARCHAR(255) CHARACTER SET utf8mb4 '
        + 'COLLATE utf8mb4_0900_ai_ci NULL AFTER `name`');
  });

  it('quotes a text default and leaves numbers and functions alone', () => {
    expect(build({op: 'add-column', newName: 'kind', type: 'VARCHAR(16)', nullable: false, defaultValue: 'it\'s'}))
      .toContain('NOT NULL DEFAULT \'it\'\'s\'');
    expect(build({op: 'add-column', newName: 'n', type: 'INT', defaultValue: '0'})).toContain('DEFAULT 0');
    expect(build({op: 'add-column', newName: 'at', type: 'TIMESTAMP', defaultValue: 'CURRENT_TIMESTAMP'}))
      .toContain('DEFAULT CURRENT_TIMESTAMP');
  });

  it('starts a MODIFY from the column as it is, so nothing is silently dropped', () => {
    const state = fromExistingColumn({...EMPTY_BUILDER, op: 'modify-column'}, {
      columnName: 'code', typeName: 'VARCHAR', columnSize: 32, decimalDigits: 0, isNullable: 'NO',
      columnDefault: null, characterSet: 'latin1', collation: 'latin1_swedish_ci'});

    expect(buildStatement('app_main', 'account', state)).toBe('ALTER TABLE `app_main`.`account`\n'
      + '  MODIFY COLUMN `code` VARCHAR(32) CHARACTER SET latin1 COLLATE latin1_swedish_ci NOT NULL');
  });

  it('creates and drops indexes, and drops a primary key as one', () => {
    expect(build({op: 'add-index', index: 'idx_account_code', indexColumns: ['code', 'site_id'], unique: true}))
      .toBe('CREATE UNIQUE INDEX `idx_account_code`\n  ON `app_main`.`account` (`code`, `site_id`)');
    expect(build({op: 'drop-index', index: 'idx_account_code'})).toBe('DROP INDEX `idx_account_code` ON `app_main`.`account`');
    expect(build({op: 'drop-index', index: 'PRIMARY'})).toBe('ALTER TABLE `app_main`.`account`\n  DROP PRIMARY KEY');
  });

  it('converts the whole table', () => {
    expect(build({op: 'convert', charset: 'utf8mb4', collation: 'utf8mb4_0900_ai_ci'}))
      .toBe('ALTER TABLE `app_main`.`account`\n  CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci');
  });

  it('states an algorithm after a comma on ALTER TABLE and after a space on CREATE and DROP INDEX', () => {
    expect(build({op: 'convert', charset: 'utf8mb4', algorithm: 'COPY'}))
      .toBe('ALTER TABLE `app_main`.`account`\n  CONVERT TO CHARACTER SET utf8mb4,\n  ALGORITHM=COPY');
    expect(build({op: 'add-index', index: 'idx_code', indexColumns: ['code'], algorithm: 'INPLACE'}))
      .toBe('CREATE INDEX `idx_code`\n  ON `app_main`.`account` (`code`)\n  ALGORITHM=INPLACE');
    expect(build({op: 'drop-index', index: 'idx_code', algorithm: 'INSTANT'}))
      .toBe('DROP INDEX `idx_code` ON `app_main`.`account`\n  ALGORITHM=INSTANT');
  });

  it('says what is missing instead of writing half a statement', () => {
    expect(build({op: 'add-index', index: 'x'})).toBeNull();
    expect(builderMissing({...EMPTY_BUILDER, op: 'rename-column', column: 'a'})).toEqual(['a new name']);
  });

  it('escapes backticks and restates sized and scaled types', () => {
    expect(quote('we`ird')).toBe('`we``ird`');
    expect(declaredType({columnName: 'p', typeName: 'DECIMAL', columnSize: 10, decimalDigits: 2, isNullable: 'YES',
      columnDefault: null, characterSet: null, collation: null})).toBe('DECIMAL(10,2)');
  });
});
