/*
 * Copyright 2020 Eike K. & Contributors
 *
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package docspell.store.impl

import java.time.LocalDate

import cats.effect.IO
import cats.syntax.option._
import cats.syntax.traverse._
import fs2.Stream

import docspell.common._
import docspell.ftsclient.FtsResult
import docspell.ftsclient.FtsResult.{AttachmentData, ItemMatch}
import docspell.logging.Level
import docspell.store._
import docspell.store.impl.TempFtsTable.Row
import docspell.store.qb.DSL._
import docspell.store.qb._
import docspell.store.queries.{QItem, Query}
import docspell.store.records.{RCollective, RItem}

import doobie._

class TempFtsTableTest extends DatabaseTest {
  private[this] val logger = docspell.logging.getLogger[IO]
  override def rootMinimumLevel = Level.Info

  override def munitFixtures = postgresAll ++ mariaDbAll ++ h2All

  def id(str: String): Ident = Ident.unsafe(str)

  def stores: (Store[IO], Store[IO], Store[IO]) =
    (pgStore(), mariaStore(), h2Store())

  test("create temporary table") {
    val (pg, maria, h2) = stores
    for {
      _ <- assertCreateTempTable(pg)
      _ <- assertCreateTempTable(maria)
      _ <- assertCreateTempTable(h2)
    } yield ()
  }

  test("query items sql") {
    val (pg, maria, h2) = stores
    for {
      _ <- prepareItems(pg)
      _ <- prepareItems(maria)
      _ <- prepareItems(h2)
      _ <- assertQueryItem(pg, ftsResults(10, 10))
      _ <- assertQueryItem(pg, ftsResults(3000, 500))
      _ <- assertQueryItem(pg, ftsResults(3000, 500))
      _ <- assertQueryItem(maria, ftsResults(10, 10))
      _ <- assertQueryItem(maria, ftsResults(3000, 500))
      _ <- assertQueryItem(h2, ftsResults(10, 10))
      _ <- assertQueryItem(h2, ftsResults(3000, 500))
    } yield ()
  }

  def prepareItems(store: Store[IO]) =
    for {
      _ <- store.transact(RCollective.insert(makeCollective(DocspellSystem.user)))
      items = (0 until 200)
        .map(makeItem(_, DocspellSystem.user))
        .toList
      _ <- items.traverse(i => store.transact(RItem.insert(i)))
    } yield ()

  def assertCreateTempTable(store: Store[IO]) = {
    val insertRows =
      List(
        Row(id("abc-def"), None, None),
        Row(id("abc-123"), Some(1.56), None),
        Row(id("zyx-321"), None, None)
      )
    val create =
      for {
        table <- TempFtsTable.createTable(store.dbms, "tt")
        n <- table.insertAll(insertRows)
        _ <- table.createIndex
        rows <- Select(select(table.all), from(table))
          .orderBy(table.id)
          .build
          .query[Row]
          .to[List]
      } yield (n, rows)

    val verify =
      store.transact(create).map { case (inserted, rows) =>
        if (store.dbms != Db.MariaDB) {
          assertEquals(inserted, 3)
        }
        assertEquals(rows, insertRows.sortBy(_.id))
      }

    verify *> verify
  }

  def assertQueryItem(store: Store[IO], ftsResults: Stream[ConnectionIO, FtsResult]) =
    for {
      today <- IO(LocalDate.now())
      account = DocspellSystem.account
      tempTable = ftsResults
        .through(TempFtsTable.prepareTable(store.dbms, "fts_result"))
        .compile
        .lastOrError
      q = Query(Query.Fix(account, None, None), Query.QueryExpr(None))
      timed <- Duration.stopTime[IO]
      items <- store
        .transact(
          tempTable.flatMap(t =>
            QItem
              .queryItems(q, today, 0, Batch.limit(10), t.some)
              .compile
              .to(List)
          )
        )
      duration <- timed
      _ <- logger.info(s"Join took: ${duration.formatExact}")

    } yield {
      assert(items.nonEmpty)
      assert(items.head.context.isDefined)
    }

  def ftsResult(start: Int, end: Int): FtsResult = {
    def matchData(n: Int): List[ItemMatch] =
      List(
        ItemMatch(
          id(s"m$n"),
          id(s"item-$n"),
          DocspellSystem.user,
          math.random(),
          FtsResult.ItemData
        ),
        ItemMatch(
          id(s"m$n-1"),
          id(s"item-$n"),
          DocspellSystem.user,
          math.random(),
          AttachmentData(id(s"item-$n-attach-1"), "attachment.pdf")
        )
      )

    val hl =
      (start until end)
        .flatMap(n =>
          List(
            id(s"m$n-1") -> List("this *a test* please"),
            id(s"m$n") -> List("only **items** here")
          )
        )
        .toMap

    FtsResult.empty
      .copy(
        count = end,
        highlight = hl,
        results = (start until end).toList.flatMap(matchData)
      )
  }

  def ftsResults(len: Int, chunkSize: Int): Stream[ConnectionIO, FtsResult] = {
    val chunks = len / chunkSize
    Stream.range(0, chunks).map { n =>
      val start = n * chunkSize
      val end = start + chunkSize
      ftsResult(start, end)
    }
  }

  def makeCollective(cid: Ident): RCollective =
    RCollective(cid, CollectiveState.Active, Language.English, true, Timestamp.Epoch)

  def makeItem(n: Int, cid: Ident): RItem =
    RItem(
      id(s"item-$n"),
      cid,
      s"item $n",
      None,
      "test",
      Direction.Incoming,
      ItemState.Created,
      None,
      None,
      None,
      None,
      None,
      None,
      Timestamp.Epoch,
      Timestamp.Epoch,
      None,
      None
    )
}
