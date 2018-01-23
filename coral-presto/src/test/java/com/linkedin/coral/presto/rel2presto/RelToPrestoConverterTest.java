package com.linkedin.coral.presto.rel2presto;

import com.facebook.presto.sql.parser.ParsingOptions;
import com.facebook.presto.sql.parser.SqlParser;
import com.facebook.presto.sql.tree.Statement;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.tools.FrameworkConfig;
import org.testng.annotations.BeforeTest;
import org.testng.annotations.Test;

import static com.linkedin.coral.presto.rel2presto.TestTable.*;
import static com.linkedin.coral.presto.rel2presto.TestUtils.*;
import static org.testng.Assert.*;


/**
 * Tests conversion from Calcite RelNode to Presto Sql
 */
// All tests use a starting sql and use calcite parser to generate parse tree.
// This makes it easier to generate RelNodes for testing. The input sql is
// in Calcite sql syntax (not Hive)
// Disabled tests are failing tests
public class RelToPrestoConverterTest {

  static FrameworkConfig config;
  static SqlParser prestoParser = new SqlParser();
  static final String tableOne = TABLE_ONE.getTableName();
  static final String tableTwo = TABLE_TWO.getTableName();

  @BeforeTest
  public static void beforeTest() {
    config = TestUtils.createFrameworkConfig(TABLE_ONE, TABLE_TWO);
  }

  private void testConversion(String inputSql, String expectedSql) {
    String prestoSql = toPrestoSql(inputSql);
    validate(prestoSql, expectedSql);
  }

  private void validate(String prestoSql, String expected) {
    try{
      Statement statement = prestoParser.createStatement(prestoSql, new ParsingOptions());
      assertNotNull(statement);
    } catch (Exception e) {
      assertTrue(false, "Failed to parse sql: " + prestoSql);
    }
    assertEquals(prestoSql, expected);
  }

  private String toPrestoSql(String sql) {
    RelToPrestoConverter converter = new RelToPrestoConverter();
    return converter.convert(TestUtils.toRel(sql, config));
  }

  @Test
  public void testSimpleSelect() {
    String sql = String.format("SELECT scol, sum(icol) as s from %s where dcol > 3.0 AND icol < 5 group by scol having sum(icol) > 10" +
            " order by scol ASC",
        tableOne);

    String expectedSql = formatSql("SELECT scol as SCOL, SUM(icol) AS s FROM " + tableOne +
        " where dcol > 3.0 and icol < 5\n"
        + "group by scol\n"
        + "having sum(icol) > 10\n"
        + "order by scol");
    testConversion(sql, expectedSql);
  }

  // different data types
  @Test
  public void testTypes() {
    // Array
    {
      String sql = "select acol[10] from tableOne";
      String expected = "SELECT \"acol\"[10]\nFROM \"tableOne\"";
      testConversion(sql, expected);
    }
    {
      String sql = "select ARRAY[1,2,3]";
      String expected = "SELECT ARRAY[1, 2, 3]\nFROM (VALUES  (0))";
      testConversion(sql, expected);
    }
    // date and timestamp
    {
      String sql = "SELECT date '2017-10-21'";
      String expected = "SELECT DATE '2017-10-21'\nFROM (VALUES  (0))";
      testConversion(sql, expected);
    }
    {
      String sql = "SELECT time '13:45:21.011'";
      String expected = "SELECT TIME '13:45:21.011'\nFROM (VALUES  (0))";
      testConversion(sql, expected);
    }
    // TODO: Test disabled: Calcite parser does not support time with timezone. Check Hive
    /*
    {
      String sql = "SELECT time '13:45:21.011 America/Cupertino'";
      String expected = "SELECT TIME '13:45:21.011 America/Cupertino'\nFROM (VALUES  (0))";
      testConversion(sql, expected);
    }
    */
  }

  // FIXME: This conversion is not correct
  @Test (enabled = false)
  public void testRowSelection() {
    String sql = "SELECT ROW(1, 2.5, 'abc')";
    String expected = "SELECT ROW(1, 2.5, 'abc')\nFROM (VALUES  (0))";
    System.out.println(RelOptUtil.toString(toRel(sql, config)));
    testConversion(sql, expected);
  }

  @Test (enabled = false)
  public void testMapSelection() {
    // TODO: This statement does not parse in calcite Sql. Fix syntax
    String sql = "SELECT MAP(ARRAY['a', 'b'], ARRAY[1, 2])";
    String expected = "SELECT MAP(ARRAY['a', 'b'], ARRAY[1, 2])\nFROM (VALUES  (0))";
    System.out.println(RelOptUtil.toString(toRel(sql, config)));
    testConversion(sql, expected);
  }

  @Test
  public void testConstantExpressions() {
    {
      String sql = "SELECT 1";
      String expected = formatSql("SELECT 1 FROM (VALUES  (0))");
      testConversion(sql, expected);
    }
    {
      String sql = "SELECT 5 + 2 * 10 / 4";
      String expected = formatSql("SELECT 5 + 2 * 10 / 4 FROM (VALUES  (0))");
      testConversion(sql, expected);
    }
  }

  // FIXME: this is disabled because the default tables are created
  // with NOT NULL definition. So the translation is not correct
  @Test (enabled = false)
  public void testIsNull() {
    {
      String sql = "SELECT icol from tableOne where icol is not null";
      String expected = formatSql("select icol from tableOne where icol IS NOT NULL");
      System.out.println(RelOptUtil.toString(toRel(sql, config)));
      testConversion(sql, expected);
    }
  }
  // window clause tests
  @Test
  public void testWindowClause() {

  }

  @Test
  public void testExists() {
    String sql = "SELECT icol from tableOne where exists (select ifield from tableTwo where dfield > 32.00)";
    String expected = quoteColumns("SELECT tableOne.icol AS ICOL\n" +
        "FROM tableOne\n" +
        "LEFT JOIN (SELECT MIN(TRUE) AS \"$f0\"\n"+
        "FROM tableTwo\n" +
        "WHERE dfield > 32.00) AS \"t2\" ON TRUE\n" +
        "WHERE \"t2\".\"$f0\" IS NOT NULL");
    testConversion(sql, expected);
  }

  @Test
  public void testNotExists() {
    String sql = "SELECT icol from tableOne where not exists (select ifield from tableTwo where dfield > 32.00)";
    String expected = quoteColumns("SELECT tableOne.icol AS ICOL\n" +
    "FROM tableOne\n" +
    "LEFT JOIN (SELECT MIN(TRUE) AS \"$f0\"\n" +
    "FROM tableTwo\n" +
    "WHERE dfield > 32.00) AS \"t2\" ON TRUE\n" +
    "WHERE NOT \"t2\".\"$f0\" IS NOT NULL");
    testConversion(sql, expected);
  }

  // Sub query types
  @Test
  public void testInClause() {
      String sql = "SELECT tcol, scol\n" + "FROM " + tableOne + " WHERE icol IN ( " + " SELECT ifield from " + tableTwo
          + "   WHERE ifield < 10)";

      String s = "select tableOne.tcol as tcol, tableOne.scol as scol\n" + "FROM " + tableOne + "\n"
          + "INNER JOIN (select ifield as ifield\n" + "from " + tableTwo + "\n" + "where ifield < 10\n"
          + "group by ifield) as \"t1\" on tableOne.icol = \"t1\".\"IFIELD\"";
      String expectedSql = quoteColumns(upcaseKeywords(s));
      testConversion(sql, expectedSql);
  }

  @Test(enabled = false)
  public void testNotIn() {
    String sql = "SELECT tcol, scol\n" + "FROM " + tableOne + " WHERE icol NOT IN ( " + " SELECT ifield from " + tableTwo
        + "   WHERE ifield < 10)";

    String s = "select tableOne.tcol as tcol, tableOne.scol as scol\n" + "FROM " + tableOne + "\n"
        + "INNER JOIN (select ifield as ifield\n" + "from " + tableTwo + "\n" + "where ifield < 10\n"
        + "group by ifield) as \"t1\" on tableOne.icol != \"t1\".\"IFIELD\"";
    String expectedSql = quoteColumns(upcaseKeywords(s));
    System.out.println(RelOptUtil.toString(toRel(sql, config)));
    testConversion(sql, expectedSql);
  }

  @Test
  public void testExceptClause() {
    String sql = "SELECT icol from " + tableOne + " EXCEPT (select ifield from " + tableTwo + ")";
    String expected = formatSql("select icol as icol from tableOne except select ifield as ifield from tableTwo");
    testConversion(sql, expected);
  }

  @Test (enabled = false)
  public void testScalarSubquery() {
    String sql = "SELECT icol from tableOne where icol > (select sum(ifield) from tableTwo)";
    System.out.println(RelOptUtil.toString(toRel(sql, config)));
    testConversion(sql, "");
  }

  @Test (enabled = false)
  public void testCorrelatedSubquery() {
    String sql = "select dcol from tableOne where dcol > (select sum(dfield) from tableTwo where dfield < tableOne.icol)";
    System.out.println(RelOptUtil.toString(toRel(sql, config)));
    testConversion(sql, "");
  }


  // Lateral and Unnest unit tests have incorrect looking sql as expected sql. That's because
  // we use calcite parser and sql2rel converters which generate incorrect plan.
  // TODO: Replace these with more reliable tests
  @Test
  public void testLateralView() {
    // we need multiple lateral clauses and projection of columns
    // other than those from lateral view for more robust testing
    final String sql = ""
        + "select icol, i_plusOne, d_plusTen, tcol, acol "
        + "from tableOne as t, "
        + "     lateral (select t.icol + 1 as i_plusOne"
        + "              from (values(true))), "
        + "     lateral (select t.dcol + 10 as d_plusTen"
        + "               from (values(true)))";

    final String expected = ""
        + "SELECT \"$cor1\".\"icol\" AS \"ICOL\", \"$cor1\".\"I_PLUSONE\", "
        + "\"$cor1\".\"D_PLUSTEN\", \"$cor1\".\"tcol\" AS \"TCOL\", \"$cor1\".\"acol\" AS \"ACOL\"\n"
        + "FROM (\"tableOne\" AS \"$cor0\"\n"
        + "CROSS JOIN (SELECT \"$cor0\".\"icol_0\" + 1 AS \"I_PLUSONE\"\n"
        + "FROM (VALUES  (TRUE))) AS \"t0\") AS \"$cor1\"\n"
        + "CROSS JOIN (SELECT \"$cor1\".\"dcol_1\" + 10 AS \"D_PLUSTEN\"\n"
        + "FROM (VALUES  (TRUE))) AS \"t2\"";
    testConversion(sql, expected);
  }

  @Test
  public void testUnnestConstant() {
    final String sql = ""
        + "SELECT c1 + 2\n"
        + "FROM UNNEST(ARRAY[(1, 1),(2, 2), (3, 3)]) as t(c1, c2)";

    final String expected = ""
        + "SELECT \"col_0\" + 2\n"
        + "FROM UNNEST(ARRAY[ROW(1, 1), ROW(2, 2), ROW(3, 3)]) AS \"t0\" (\"col_0\", \"col_1\")";
    testConversion(sql, expected);
  }

  // Expected SQL has badly aliased names and column names because of errors in calcite
  // SQL parser. We need to replace this with more reliable test. For now, this is better than
  // not having any test.
  @Test
  public void testLateralViewUnnest() {
    String sql = "select icol, acol_elem from tableOne as t cross join unnest(t.acol) as t1(acol_elem)";
    System.out.println(RelOptUtil.toString(toRel(sql, config)));
    String expectedSql = ""
        + "SELECT \"$cor0\".\"icol\" AS \"ICOL\", \"$cor0\".\"acol0\" AS \"ACOL_ELEM\"\n"
        + "FROM \"tableOne\" AS \"$cor0\"\n"
        + "CROSS JOIN UNNEST(\"$cor0\".\"acol_4\") AS \"t0\" (\"acol\")";
    testConversion(sql, expectedSql);
  }

  @Test
  public void testMultipleNestedQueries() {
    String sql = "select icol from tableOne where dcol > (select avg(dfield) from tableTwo where dfield > " +
        "   (select sum(ifield) from tableOne) )";
    System.out.println(RelOptUtil.toString(toRel(sql, config)));
  }

  // set queries
  @Test
  public void testUnion() throws Exception {
    testSetQueries("UNION");
  }

  @Test
  public void testIntersect() throws Exception {
    testSetQueries("INTERSECT");
  }

  @Test
  public void testExcept() throws Exception {
    testSetQueries("EXCEPT");
  }

  private void testSetQueries(String operator) throws Exception {
    String sql = "SELECT icol FROM " + tableOne + " " +  operator + "\n" +
        "SELECT ifield FROM " + TABLE_TWO.getTableName() + " WHERE sfield = 'abc'";
    String expectedSql = formatSql("SELECT icol as icol FROM " + tableOne + " " +
        operator +
        " SELECT ifield as ifield from " + tableTwo + " " +
        "where sfield = 'abc'");
    testConversion(sql, expectedSql);
  }

  @Test
  public void testCast() throws Exception {
    String sql = "SELECT cast(dcol as integer) as d, cast(icol as double) as i "
        + "FROM " + TABLE_ONE.getTableName();
    String expectedSql = formatSql("SELECT CAST(dcol as integer) as d, cast(icol as double) as i" +
    " from " + tableOne);
    testConversion(sql, expectedSql);
  }

  @Test
  public void testRand() throws Exception {
    String sql1 = "SELECT icol, rand() "
        + "FROM " + TABLE_ONE.getTableName();
    String expectedSql1 = formatSql("SELECT icol AS \"ICOL\", \"RANDOM\"()" +
        " from " + tableOne);
    testConversion(sql1, expectedSql1);

    String sql2 = "SELECT icol, rand(1) "
        + "FROM " + TABLE_ONE.getTableName();
    String expectedSql2 = formatSql("SELECT icol AS \"ICOL\", \"RANDOM\"()" +
        " from " + tableOne);
    testConversion(sql2, expectedSql2);
  }

  @Test
  public void testRandInteger() throws Exception {
    String sql1 = "SELECT rand_integer(2, icol) "
        + "FROM " + TABLE_ONE.getTableName();
    String expectedSql1 = formatSql("SELECT \"RANDOM\"(icol)" +
        " from " + tableOne);
    testConversion(sql1, expectedSql1);

    String sql2 = "SELECT rand_integer(icol) "
        + "FROM " + TABLE_ONE.getTableName();
    String expectedSql2 = formatSql("SELECT \"RANDOM\"(icol)" +
        " from " + tableOne);
    testConversion(sql2, expectedSql2);
  }

  @Test
  public void testTruncate() throws Exception {
    String sql1 = "SELECT truncate(dcol) "
        + "FROM " + TABLE_ONE.getTableName();
    String expectedSql1 = formatSql("SELECT TRUNCATE(dcol)" +
        " from " + tableOne);
    testConversion(sql1, expectedSql1);

    String sql2 = "SELECT truncate(dcol, 2) "
        + "FROM " + TABLE_ONE.getTableName();
    String expectedSql2 = formatSql("SELECT \"TRUNCATE\"(dcol * POWER(10, 2)) / POWER(10, 2)" +
        " from " + tableOne);
    testConversion(sql2, expectedSql2);
  }

  @Test
  public void testSubString2() throws Exception {
    String sql = "SELECT SUBSTRING(scol FROM 1) "
        + "FROM " + TABLE_ONE.getTableName();
    String expectedSql = formatSql("SELECT \"SUBSTR\"(scol, 1)" +
        " from " + tableOne);
    testConversion(sql, expectedSql);
  }

  @Test
  public void testSubString3() throws Exception {
    String sql = "SELECT SUBSTRING(scol FROM icol FOR 3) "
        + "FROM " + TABLE_ONE.getTableName();
    String expectedSql = formatSql("SELECT \"SUBSTR\"(scol, icol, 3)" +
        " from " + tableOne);
    testConversion(sql, expectedSql);
  }
}
