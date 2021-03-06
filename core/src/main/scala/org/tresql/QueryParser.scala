package org.tresql

import scala.util.parsing.combinator.JavaTokenParsers
import scala.util.parsing.input.Reader
import sys._

trait QueryParser extends JavaTokenParsers {

  trait Exp {
    def tresql: String
  }

  case class Ident(ident: List[String]) extends Exp {
    def tresql = ident.mkString(".")
  }
  case class Variable(variable: String, opt: Boolean) extends Exp {
    def tresql = (if (variable == "?") "?" else ":" + variable) + (if (opt) "?" else "")
  }
  case class Id(name: String) extends Exp {
    def tresql = "#" + name
  }
  case class IdRef(name: String) extends Exp {
    def tresql = ":#" + name
  }
  case class Res(rNr: Int, col: Any) extends Exp {
    def tresql = ":" + rNr + "(" + any2tresql(col) + ")"
  }
  case class UnOp(operation: String, operand: Any) extends Exp {
    def tresql = operation + any2tresql(operand)
  }
  case class Fun(name: String, parameters: List[Any], distinct: Boolean) extends Exp {
    def tresql = name + "(" + (if (distinct) "# " else "") +
      ((parameters map any2tresql) mkString ",") + ")"
  }
  case class In(lop: Any, rop: List[Any], not: Boolean) extends Exp {
    def tresql = any2tresql(lop) + (if (not) " not" else "") + rop.map(any2tresql(_)).mkString(
      " in(", ", ", ")")
  }
  case class BinOp(op: String, lop: Any, rop: Any) extends Exp {
    def tresql = any2tresql(lop) + " " + op + " " + any2tresql(rop)
  }

  case class Join(default: Boolean, expr: Any, noJoin: Boolean) extends Exp {
    def tresql = this match {
      case Join(_, _, true) => ";"
      case Join(false, a: Arr, false) => a.tresql
      case Join(false, e: Exp, false) => "[" + e.tresql + "]"
      case Join(true, null, false) => "/"
      case Join(true, e, false) => "/[" + any2tresql(e) + "]"
    }
  }
  val NoJoin = Join(false, null, true)
  val DefaultJoin = Join(true, null, false)

  case class Obj(obj: Any, alias: String, join: Join, outerJoin: String, nullable: Boolean) extends Exp {
    def tresql = (if (join != null) join.tresql else "") + (if (outerJoin == "r") "?" else "") +
      any2tresql(obj) + (if (outerJoin == "l") "?" else "") + (if (alias != null) " " + alias else "")
  }
  case class Col(col: Any, alias: String, typ: String) extends Exp {
    def tresql = any2tresql(col) + (if (typ != null) " :" + typ else "") +
      (if (alias != null) " " + alias else "")
  }
  case class Cols(distinct: Boolean, cols: List[Col]) extends Exp {
    def tresql = error("Not implemented")
  }
  case class Grp(cols: List[Any], having: Any) extends Exp {
    def tresql = "(" + cols.map(any2tresql(_)).mkString(",") + ")" +
      (if (having != null) "^(" + any2tresql(having) + ")" else "")
  }
  //cols expression is tuple in the form - ([<nulls first>], <order col list>, [<nulls last>])
  case class Ord(cols: List[(Exp, Any, Exp)]) extends Exp {
    def tresql = "#(" + cols.map(c=> (if (c._1 == Null) "null " else "") +
      any2tresql(c._2) + (if (c._1 == Null) "null " else "")).mkString(",") + ")"
  }
  case class Query(tables: List[Obj], filter: Filters, cols: List[Col], distinct: Boolean,
    group: Grp, order: Ord, offset: Any, limit: Any) extends Exp {
    def tresql = tables.map(any2tresql(_)).mkString +
      filter.tresql + (if (distinct) "#" else "") +
      (if (cols != null) cols.map(_.tresql).mkString("{", ",", "}") else "") +
      (if (group != null) any2tresql(group) else "") +
      (if (order != null) order.tresql else "") +
      (if (limit != null) "@(" + (if (offset != null) any2tresql(offset) + " " else "") +
        any2tresql(limit) + ")"
      else "")
  }
  case class Insert(table: Ident, alias: String, cols: List[Col], vals: Any) extends Exp {
    def tresql = "+" + table.tresql + Option(alias).getOrElse("") +
      cols.map(_.tresql).mkString("{", ",", "}") + any2tresql(vals)
  }
  case class Update(table: Ident, alias: String, filter: Arr, cols: List[Col], vals: Any) extends Exp {
    def tresql = "=" + table.tresql + Option(alias).getOrElse("") +
      (if (filter != null) filter.tresql else "") +
      cols.map(_.tresql).mkString("{", ",", "}") + any2tresql(vals)
  }
  case class Delete(table: Ident, alias: String, filter: Arr) extends Exp {
    def tresql = "-" + table.tresql + Option(alias).getOrElse("") + filter.tresql
  }
  case class Arr(elements: List[Any]) extends Exp {
    def tresql = "[" + any2tresql(elements) + "]"
  }
  case class Filters(filters: List[Arr]) extends Exp {
    def tresql = filters map any2tresql mkString
  }
  case class All() extends Exp {
    def tresql = "*"
  }
  case class IdentAll(ident: Ident) extends Exp {
    def tresql = ident.ident.mkString(".") + ".*"
  }
  object Null extends Exp {
    def tresql = "null"
  }

  case class Braces(expr: Any) extends Exp {
    def tresql = "(" + any2tresql(expr) + ")"
  }

  val quotedStringLiteralRegexp = ("'" + """([^'\p{Cntrl}\\]|\\[\\/bfnrt']|\\u[a-fA-F0-9]{4})*""" + "'")r
  val doubleQuotedStringLiteralRegexp = ("\"" + """([^"\p{Cntrl}\\]|\\[\\/bfnrt"]|\\u[a-fA-F0-9]{4})*""" + "\"")r
  val identRegexp = """\p{javaJavaIdentifierStart}\p{javaJavaIdentifierPart}*"""r

  def quotedStringLiteral: Parser[String] = quotedStringLiteralRegexp ^^
    (s => s.substring(1, s.length - 1).replace("\\'", "'"))
  def doubleQuotedStringLiteral: Parser[String] = doubleQuotedStringLiteralRegexp ^^
    (s => s.substring(1, s.length - 1).replace("\\\"", "\""))
  override def stringLiteral: Parser[String] = quotedStringLiteral | doubleQuotedStringLiteral
  //for performance reasons, do not initialize regexp on each invocation
  override def ident: Parser[String] = identRegexp

  val KEYWORDS = Set("in", "null")
  def excludeKeywordsIdent = new Parser[String] {
    def apply(in: Input) = {
      ident(in) match {
        case s @ Success(r, i) => if (!KEYWORDS.contains(r)) s
        else Failure("illegal identifier: " + r, i)
        case r => r
      }
    }
  }
  def comment: Parser[String] = """/\*(.|[\r\n])*?\*/""".r
  def decimalNr: Parser[BigDecimal] = decimalNumber ^^ (BigDecimal(_))
  def TRUE: Parser[Boolean] = "true" ^^^ true
  def FALSE: Parser[Boolean] = "false" ^^^ false
  def NULL = "null" ^^^ Null
  def ALL: Parser[All] = "*" ^^^ All()

  def qualifiedIdent: Parser[Ident] = rep1sep(excludeKeywordsIdent, ".") ^^ (Ident(_))
  def qualifiedIdentAll: Parser[IdentAll] = qualifiedIdent <~ ".*" ^^ (IdentAll(_))
  def variable: Parser[Variable] = ((":" ~> ((ident | stringLiteral) ~ opt("?"))) | "?") ^^ {
    case "?" => Variable("?", false)
    case (i: String) ~ o => Variable(i, o != None)
  }
  def id: Parser[Id] = "#" ~> ident ^^ (Id(_))
  def idref: Parser[IdRef] = ":#" ~> ident ^^ (IdRef(_))
  def result: Parser[Res] = (":" ~> "[0-9]+".r <~ "(") ~ ("[0-9]+".r | stringLiteral |
    qualifiedIdent) <~ ")" ^^ {
      case r ~ c => Res(r.toInt,
        c match {
          case s: String => try { s.toInt } catch { case _: NumberFormatException => s }
          case Ident(i) => i
        })
    }
  def bracesExp: Parser[Braces] = "(" ~> expr <~ ")" ^^ (Braces(_))
  /* Important is that function parser is applied before query because of the longest token
     * matching, otherwise qualifiedIdent of query will match earlier.
     * Also important is that array parser is applied after query parser because it matches join parser
     * of the query.
     * Also important is that variable parser is after query parser since ? mark matches variable
     * Also important that insert, delete, update parsers are before query parser */
  def operand: Parser[Any] = (TRUE | FALSE | NULL | ALL | function | insert | update | query |
    decimalNr | stringLiteral | variable | id | idref | result | array)
  def negation: Parser[UnOp] = "-" ~> operand ^^ (UnOp("-", _))
  def not: Parser[UnOp] = "!" ~> operand ^^ (UnOp("!", _))
  //is used within column clause to indicate separate query
  def sep: Parser[UnOp] = "|" ~> operand ^^ (UnOp("|", _))
  //is used in order by desc
  def desc: Parser[UnOp] = "~" ~> operand ^^ (UnOp("~", _))
  def function: Parser[Fun] = (qualifiedIdent <~ "(") ~ opt("#") ~ repsep(expr, ",") <~ ")" ^^ {
    case Ident(a) ~ d ~ b => Fun(a.mkString("."), b, d.map(x=> true).getOrElse(false))
  }
  def array: Parser[Arr] = "[" ~> repsep(expr, ",") <~ "]" ^^ (Arr(_))

  //query parsers
  def join: Parser[Join] = (("/" ~ opt("[" ~> expr <~ "]")) | (opt("[" ~> expr <~ "]") ~ "/") |
    ";" | array) ^^ {
      case ";" => NoJoin
      case "/" ~ Some(e) => Join(true, e, false)
      case "/" ~ None => DefaultJoin
      case Some(e) ~ "/" => Join(true, e, false)
      case None ~ "/" => DefaultJoin
      case a => Join(false, a, false)
    }
  def filter: Parser[Arr] = array
  def filters: Parser[Filters] = rep(filter) ^^ (Filters(_))
  def obj: Parser[Obj] = opt(join) ~ opt("?") ~ (qualifiedIdent | bracesExp) ~
    opt("?") ~ opt(excludeKeywordsIdent) ~ opt("?") ^^ {
      case a ~ Some(b) ~ c ~ Some(d) ~ e ~ f => error("Cannot be right and left join at the same time")
      case a ~ Some(b) ~ c ~ d ~ e ~ Some(f) => error("Cannot be right and left join at the same time")
      case a ~ b ~ c ~ d ~ e ~ f => Obj(c, e.orNull, a.orNull,
        b.map(x => "r") orElse d.orElse(f).map(x => "l") orNull,
        //set nullable flag if left outer join
        d orElse f map (x => true) getOrElse (false))
    }
  def objs: Parser[List[Obj]] = rep1(obj) ^^ { l =>
    var prev: Obj = null
    val res = l.flatMap { thisObj =>
      val (prevObj, prevAlias) = (prev, if (prev == null) null else prev.alias)
      prev = thisObj
      //process foreign key shortcut join
      thisObj match {
        case o @ Obj(_, a, j @ Join(false, Arr(l @ List(o1 @ Obj(_, a1, _, oj1, n1), _*)), false), oj, n) =>
          //o1.alias prevail over o.alias, o.{outerJoin, nullable} prevail over o1.{outerJoin, nullable}
          List(o.copy(alias = (Option(a1).getOrElse(a)), join =
            j.copy(expr = o1.copy(alias = null, outerJoin = null, nullable = false)),
            outerJoin = (if (oj == null) oj1 else oj), nullable = n || n1)) ++
            (if (prevObj == null) List() else l.tail.flatMap {
              //flattenize array of foreign key shortcut joins
              case o2 @ Obj(_, a2, _, oj2, n2) => List((if (prevAlias != null) Obj(Ident(List(prevAlias)),
                null, NoJoin, null, false)
              else Obj(prevObj.obj, null, NoJoin, null, false)),
                o.copy(alias = (if (a2 != null) a2 else o.alias), join =
                  j.copy(expr = o2.copy(alias = null, outerJoin = null, nullable = false)),
                  outerJoin = (if (oj == null) oj2 else oj), nullable = n || n2))
              case x => List(o.copy(join = Join(false, x, false)))
            })
        case o => List(o)
      }
    }
    Vector(res: _*).lastIndexWhere(_.outerJoin == "r") match {
      case -1 => res
      //set nullable flag for all objs right to the last obj with outer join
      case x => res.zipWithIndex.map(t => if (t._2 < x && !t._1.nullable) t._1.copy(nullable = true) else t._1)
    }
  }
  def column = new Parser[Col] {
    def parser = (qualifiedIdentAll |
      (expr ~ opt(":" ~> ident) ~ opt(stringLiteral | qualifiedIdent))) ^^ {
        case i: IdentAll => Col(i, null, null)
        case (o @ Obj(_, a, _, _, _)) ~ (typ: Option[String]) ~ None => Col(o, a, typ orNull)
        case e ~ (typ: Option[String]) ~ (a: Option[_]) => Col(e, a map {
          case Ident(i) => i.mkString; case s => "\"" + s + "\""
        } orNull, typ orNull)
      }
    def extractAlias(expr: Any): String = expr match {
      case BinOp(_, lop, rop) => extractAlias(rop)
      case UnOp(_, op) => extractAlias(op)
      case Obj(_, alias, _, null, _) => alias
      case _ => null
    }
    def apply(in: Input) = parser(in) match {
      case r @ Success(Col(_: IdentAll | _: Obj, _, _), i) => r
      case s @ Success(c @ Col(e, null, _), i) => extractAlias(e) match {
        case null => s
        case x => Success(c.copy(alias = x), i)
      }
      case r => r
    }
  }
  def columns: Parser[Cols] = (opt("#") <~ "{") ~ rep1sep(column, ",") <~ "}" ^^ {
    case d ~ c => Cols(d != None, c)
  }
  def group: Parser[Grp] = ("(" ~> rep1sep(expr, ",") <~ ")") ~
    opt(("^" ~ "(") ~> expr <~ ")") ^^ { case g ~ h => Grp(g, if (h == None) null else h.get) }
  def orderMember: Parser[(Exp, Any, Exp)] = opt(NULL) ~ expr ~ opt(NULL) ^^ {
    case Some(nf) ~ e ~ Some(nl) => error("Cannot be nulls first and nulls last at the same time")
    case nf ~ e ~ nl => (if (nf == None) null else nf.get, e, if (nl == None) null else nl.get)
  }
  def order: Parser[Ord] = ("#" ~ "(") ~> rep1sep(orderMember, ",") <~ ")" ^^ (Ord(_)) 
  def offsetLimit: Parser[(Any, Any)] = ("@" ~ "(") ~> ("[0-9]+".r | variable) ~ opt(",") ~
    opt("[0-9]+".r | variable) <~ ")" ^^ { pr =>
      {
        def c(x: Any) = x match { case v: Variable => v case s: String => BigDecimal(s) }
        pr match {
          case o ~ comma ~ Some(l) => (c(o), c(l))
          case o ~ Some(comma) ~ None => (c(o), null)
          case o ~ None ~ None => (null, c(o))
        }
      }
    }
  def query: Parser[Any] = new Parser[Any] {
    def parser = objs ~ filters ~ opt(columns) ~ opt(group) ~ opt(order) ~
      opt(offsetLimit) ^^ {
        case (t :: Nil) ~ Filters(Nil) ~ None ~ None ~ None ~ None => t
        case t ~ f ~ c ~ g ~ o ~ l => Query(t, f, c.map(_.cols) orNull,
          c.map(_.distinct) getOrElse false, g.orNull, o.orNull, l.map(_._1) orNull, l.map(_._2) orNull)
      }
    def toDivChain(objs: List[Obj]): Any = objs match {
      case o :: Nil => o.obj
      case l => BinOp("/", l.head.obj, toDivChain(l.tail))
    }
    def apply(in: Input) = parser(in) match {
      //check for division chain
      case r @ Success(Query(objs, Filters(Nil), null, false, null, null, null, null), i) =>
        if (objs.forall {
          case Obj(_, null, DefaultJoin, null, _) | Obj(_, null, null, null, _) => true
          case _ => false
        }) Success(toDivChain(objs), i) /*division chain found*/ else r
      case r => r
    }
  }
  def queryWithCols: Parser[Any] = new Parser[Any] {
    def apply(in: Input) = query(in) match {
      case r @ Success(Query(objs, _, cols, _, _, _, _, _), i) if cols != null => r
      case r @ Success(x, i) => Failure("Query must contain column clause: " + x, i)
      case r => r
    }
  }
  def insert: Parser[Insert] = (("+" ~> qualifiedIdent ~ opt(excludeKeywordsIdent) ~ opt(columns) ~
    opt(queryWithCols | repsep(array, ","))) |
    ((qualifiedIdent ~ opt(excludeKeywordsIdent) ~ opt(columns) <~ "+") ~ rep1sep(array, ","))) ^^ {
      case t ~ a ~ c ~ (v: Option[_]) => Insert(t, a.orNull, c.map(_.cols).orNull, v.orNull)
      case t ~ a ~ c ~ v => Insert(t, a.orNull, c.map(_.cols).orNull, v)
    }
  def update: Parser[Update] = (("=" ~> qualifiedIdent ~ opt(excludeKeywordsIdent) ~
    opt(filter) ~ opt(columns) ~ opt(queryWithCols | array)) |
    ((qualifiedIdent ~ opt(excludeKeywordsIdent) ~ opt(filter) ~ opt(columns) <~ "=") ~ array)) ^^ {
      case t ~ a ~ f ~ c ~ v => Update(t, a orNull, f orNull, c match {
        case Some(Cols(_, c)) => c
        case None => null
      }, v match {
        case a: Arr => a
        case Some(vals) => vals
        case None => null
      })
    }
  def delete: Parser[Delete] = (("-" ~> qualifiedIdent ~ opt(excludeKeywordsIdent) ~ filter) |
    (((qualifiedIdent ~ opt(excludeKeywordsIdent)) <~ "-") ~ filter)) ^^ {
      case t ~ a ~ f => Delete(t, a orNull, f)
    }
  //operation parsers
  //delete must be before negation since it can start with - sign and
  //before operand so it is not translated into minus expression!
  def unaryExpr = delete | operand | negation | not | sep | desc
  def mulDiv: Parser[Any] = unaryExpr ~ rep("*" ~ unaryExpr | "/" ~ unaryExpr) ^^ (binOp(_))
  def plusMinus: Parser[Any] = mulDiv ~ rep(("++" | "+" | "-" | "&&" | "||") ~ mulDiv) ^^ (binOp(_))
  def comp: Parser[~[Any, List[~[String, Any]]]] = plusMinus ~
    rep(("<=" | ">=" | "<" | ">" | "!=" | "=" | "~~" | "!~~" | "~" | "!~" | "in" | "!in") ~ plusMinus)
  //this is for friendly error message
  def compTernary = new Parser[Any] {
    def apply(in: Input) = comp(in) match {
      case s @ Success(r, i) => try Success(compBinOp(r), i) catch {
        case e => Failure(e.getMessage, i)
      }
      case r => r
    }
    def compBinOp(p: ~[Any, List[~[String, Any]]]): Any = p match {
      case lop ~ Nil => lop
      case lop ~ ((o ~ rop) :: Nil) => BinOp(o, lop, rop)
      case lop ~ List((o1 ~ mop), (o2 ~ rop)) => BinOp("&", BinOp(o1, lop, mop), BinOp(o2, mop, rop))
      case lop ~ x => error("Ternary comparison operation is allowed, however, here " + (x.size + 1) +
        " operands encountered.")
    }
  }
  def in: Parser[In] = plusMinus ~ opt("!") ~ "in" ~ "(" ~ rep1sep(plusMinus, ",") <~ ")" ^^ {
    case lop ~ not ~ "in" ~ "(" ~ rop => In(lop, rop, not != None)
  }
  //in parser should come before comp so that it is not taken for in function which is illegal
  def logicalOp = in | compTernary
  def logical: Parser[Any] = logicalOp ~ rep("&" ~ logicalOp | "|" ~ logicalOp) ^^ (binOp(_))

  //expression
  def expr: Parser[Any] = opt(comment) ~> logical <~ opt(comment)

  def exprList: Parser[Any] = repsep(expr, ",") ^^ {
    case e :: Nil => e
    case l => Arr(l)
  }

  def parseAll(expr: String): ParseResult[Any] = {
    parseAll(exprList, expr)
  }

  def parseExp(expr: String): Any = {
    Env.cache.flatMap(_.get(expr)).getOrElse {
      val e = parseAll(expr) match {
        case Success(r, _) => r
        case x => error(x.toString)
      }
      Env.cache.map(_.put(expr, e))
      e
    }
  }

  private def binOp(p: ~[Any, List[~[String, Any]]]): Any = p match {
    case lop ~ Nil => lop
    case lop ~ ((o ~ rop) :: l) => BinOp(o, lop, binOp(this.~(rop, l)))
  }

  def any2tresql(any: Any): String = any match {
    case a: String => if (a.contains("'")) "\"" + a + "\"" else "'" + a + "'"
    case e: Exp => e.tresql
    case null => "null"
    case l: List[_] => l map any2tresql mkString ", "
    case x => x.toString
  }

  def bindVariables(ex: String): List[String] = {
    var bindIdx = 0
    val vars = scala.collection.mutable.ListBuffer[String]()
    def bindVars(parsedExpr: Any): Any =
      parsedExpr match {
        case _: Ident | _: Id | _: IdRef | _: Res | _: All | _: IdentAll | Null | null =>
        case l: List[_] => l foreach bindVars
        case Variable("?", _) =>
          bindIdx += 1; vars += bindIdx.toString
        case Variable(n, _) => vars += n
        case Fun(_, pars, _) => bindVars(pars)
        case UnOp(_, operand) => bindVars(operand)
        case BinOp(_, lop, rop) =>
          bindVars(lop); bindVars(rop)
        case In(lop, rop, _) =>
          bindVars(lop); bindVars(rop)
        case Obj(t, _, j, _, _) =>
          bindVars(j); bindVars(t)
        case Join(_, j, _) => bindVars(j)
        case Col(c, _, _) => bindVars(c)
        case Cols(_, cols) => bindVars(cols)
        case Grp(cols, hv) =>
          bindVars(cols); bindVars(hv)
        case Ord(cols) => cols.foreach(c=> bindVars(c._2))
        case Query(objs, filters, cols, _, gr, ord, _, _) => {
          bindVars(objs)
          bindVars(filters)
          bindVars(cols)
          bindVars(gr)
          bindVars(ord)
        }
        case Insert(_, _, cols, vals) => {
          bindVars(cols)
          bindVars(vals)
        }
        case Update(_, _, filter, cols, vals) => {
          bindVars(filter)
          bindVars(cols)
          bindVars(vals)
        }
        case Delete(_, _, filter) => bindVars(filter)
        case Arr(els) => bindVars(els)
        case Filters(f) => bindVars(f)
        case Braces(expr) => bindVars(expr)
        //for the security
        case x => error("Unknown expression: " + x)
      }
    parseAll(ex) match {
      case Success(r, _) => {
        bindVars(r)
        vars.toList
      }
      case x => error(x.toString)
    }

  }
}

object QueryParser extends QueryParser