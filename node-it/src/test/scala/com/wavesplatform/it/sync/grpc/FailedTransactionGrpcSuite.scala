package com.wavesplatform.it.sync.grpc

import com.google.common.primitives.Longs
import com.google.protobuf.ByteString
import com.wavesplatform.account.{Address, KeyPair}
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.common.utils.{Base58, EitherExt2}
import com.wavesplatform.it.api.SyncGrpcApi._
import com.wavesplatform.it.sync._
import com.wavesplatform.it.sync.transactions.FailedTransactionSuite.mkExchange
import com.wavesplatform.it.sync.transactions.PriorityTransaction
import com.wavesplatform.it.util._
import com.wavesplatform.lang.script.Script
import com.wavesplatform.lang.v1.FunctionHeader
import com.wavesplatform.lang.v1.compiler.Terms
import com.wavesplatform.lang.v1.compiler.Terms.FUNCTION_CALL
import com.wavesplatform.lang.v1.estimator.v3.ScriptEstimatorV3
import com.wavesplatform.protobuf.Amount
import com.wavesplatform.protobuf.transaction.{PBRecipients, PBSignedTransaction, PBTransactions, Recipient}
import com.wavesplatform.state.{BinaryDataEntry, BooleanDataEntry, IntegerDataEntry, StringDataEntry}
import com.wavesplatform.transaction.assets.exchange.AssetPair
import com.wavesplatform.transaction.smart.script.ScriptCompiler

class FailedTransactionGrpcSuite extends GrpcBaseTransactionSuite with PriorityTransaction {
  import grpcApi._

  private val thirdContract     = KeyPair("thirdContract".getBytes("UTF-8"))
  private val thirdContractAddr = PBRecipients.create(Address.fromPublicKey(thirdContract.publicKey)).getPublicKeyHash
  private val caller            = thirdAcc
  private val callerAddr        = PBRecipients.create(Address.fromPublicKey(thirdAcc.publicKey)).getPublicKeyHash

  private val maxTxsInMicroBlock = sender.config.getInt("waves.miner.max-transactions-in-micro-block")

  private val assetAmount    = 1000000000L
  private var smartAsset     = ""
  private var sponsoredAsset = ""

  protected override def beforeAll(): Unit = {
    super.beforeAll()

    sender.broadcastTransfer(sender.privateKey, Recipient().withPublicKeyHash(thirdContractAddr), 100.waves, minFee, waitForTx = true)

    smartAsset = PBTransactions
      .vanillaUnsafe(
        sender
          .broadcastIssue(
            thirdContract,
            "Asset",
            assetAmount,
            8,
            reissuable = true,
            issueFee,
            description = "Description",
            script = Right(ScriptCompiler.compile("true", ScriptEstimatorV3).toOption.map(_._1)),
            waitForTx = true
          )
      )
      .id()
      .toString

    sponsoredAsset = PBTransactions
      .vanillaUnsafe(
        sender
          .broadcastIssue(
            thirdContract,
            "Sponsored Asset",
            assetAmount,
            8,
            reissuable = true,
            issueFee,
            "Description",
            script = Right(None),
            waitForTx = true
          )
      )
      .id()
      .toString

    val scriptTextV4 =
      s"""
         |{-# STDLIB_VERSION 4 #-}
         |{-# CONTENT_TYPE DAPP #-}
         |
         |let asset = base58'$smartAsset'
         |
         |@Callable(inv)
         |func tikTok() = {
         |  let action = valueOrElse(getString(this, "tikTok"), "unknown")
         |  if (action == "transfer") then [ScriptTransfer(inv.caller, 15, asset)]
         |  else if (action == "issue") then [Issue("new asset", "", 100, 8, true, unit, 0)]
         |  else if (action == "reissue") then [Reissue(asset, true, 15)]
         |  else if (action == "burn") then [Burn(asset, 15)]
         |  else []
         |}
         |
         |@Callable(inv)
         |func transferAndWrite(x: Int) = {
         |  if (x % 4 == 0) then [ScriptTransfer(inv.caller, 15, asset), IntegerEntry("n", x)]
         |  else if (x % 4 == 1) then [ScriptTransfer(inv.caller, 15, asset), BooleanEntry("b", x % 2 == 0)]
         |  else if (x % 4 == 2) then [ScriptTransfer(inv.caller, 15, asset), BinaryEntry("bn", toBytes(x))]
         |  else if (x % 4 == 3) then [ScriptTransfer(inv.caller, 15, asset), StringEntry("s", toString(x))]
         |  else []
         |}
         |
        """.stripMargin
    val script = ScriptCompiler.compile(scriptTextV4, ScriptEstimatorV3).explicitGet()._1
    sender.setScript(thirdContract, Right(Some(script)), setScriptFee, waitForTx = true)
  }

  test("InvokeScriptTransaction: insufficient action fees propagates failed transaction") {
    val invokeFee            = 0.005.waves
    val setAssetScriptMinFee = setAssetScriptFee + smartFee * 2
    val priorityFee          = setAssetScriptMinFee + invokeFee

    updateAssetScript(result = true, smartAsset, thirdContract, setAssetScriptMinFee)

    for (typeName <- Seq("transfer", "issue", "reissue", "burn")) {
      updateTikTok("unknown", setAssetScriptMinFee)

      sendTxsAndThenPriorityTx(
        _ =>
          sender
            .broadcastInvokeScript(
              caller,
              Recipient().withPublicKeyHash(thirdContractAddr),
              Some(FUNCTION_CALL(FunctionHeader.User("tikTok"), List.empty)),
              fee = invokeFee
            ),
        () => updateTikTok(typeName, priorityFee)
      )(assertFailedTxs)
    }
  }

  test("InvokeScriptTransaction: invoke script error propagates failed transaction") {
    val invokeFee            = 0.005.waves + smartFee
    val setAssetScriptMinFee = setAssetScriptFee + smartFee * 2
    val priorityFee          = setAssetScriptMinFee + invokeFee

    for (funcName <- Seq("transfer", "reissue", "burn")) {
      updateTikTok(funcName, setAssetScriptMinFee)
      updateAssetScript(result = true, smartAsset, thirdContract, setAssetScriptMinFee)

      sendTxsAndThenPriorityTx(
        _ =>
          sender
            .broadcastInvokeScript(
              caller,
              Recipient().withPublicKeyHash(thirdContractAddr),
              Some(FUNCTION_CALL(FunctionHeader.User("tikTok"), List.empty)),
              fee = invokeFee
            ),
        () => updateAssetScript(result = false, smartAsset, thirdContract, priorityFee)
      )(assertFailedTxs)
    }
  }

  test("InvokeScriptTransaction: sponsored fee on failed transaction should be charged correctly") {
    val invokeFee            = 0.005.waves + smartFee
    val invokeFeeInAsset     = invokeFee / 100000 // assetFee = feeInWaves / feeUnit * sponsorship
    val setAssetScriptMinFee = setAssetScriptFee + smartFee * 2
    val priorityFee          = setAssetScriptMinFee + invokeFee
    val totalWavesSpend      = invokeFee * maxTxsInMicroBlock * 2

    updateAssetScript(result = true, smartAsset, thirdContract, setAssetScriptMinFee)
    updateTikTok("reissue", setAssetScriptMinFee)

    sender.broadcastSponsorFee(
      thirdContract,
      Some(Amount.of(ByteString.copyFrom(Base58.decode(sponsoredAsset)), 1)),
      sponsorFee + smartFee,
      waitForTx = true
    )
    sender.broadcastTransfer(
      thirdContract,
      Recipient().withPublicKeyHash(callerAddr),
      assetAmount,
      smartMinFee,
      assetId = sponsoredAsset,
      waitForTx = true
    )
    val prevBalance = sender.wavesBalance(thirdContractAddr).regular

    sendTxsAndThenPriorityTx(
      _ =>
        sender.broadcastInvokeScript(
          caller,
          Recipient().withPublicKeyHash(thirdContractAddr),
          Some(FUNCTION_CALL(FunctionHeader.User("tikTok"), List.empty)),
          fee = invokeFeeInAsset,
          feeAssetId = ByteString.copyFrom(Base58.decode(sponsoredAsset))
        ),
      () => updateAssetScript(result = false, smartAsset, thirdContract, priorityFee)
    ) { txs =>
      sender.wavesBalance(thirdContractAddr).regular shouldBe prevBalance - totalWavesSpend - priorityFee
      assertFailedTxs(txs)
    }
  }

  test("InvokeScriptTransaction: account state should not be changed after accepting failed transaction") {
    val invokeFee            = 0.005.waves + smartFee
    val setAssetScriptMinFee = setAssetScriptFee + smartFee * 2
    val priorityFee          = setAssetScriptMinFee + invokeFee

    val initialEntries = List(
      IntegerDataEntry("n", -1),
      BooleanDataEntry("b", value = false),
      BinaryDataEntry("bn", ByteStr(Longs.toByteArray(-1))),
      StringDataEntry("s", "-1")
    ).map(PBTransactions.toPBDataEntry)
    sender.putData(thirdContract, initialEntries, minFee + smartFee)
    updateAssetScript(result = true, smartAsset, thirdContract, setAssetScriptMinFee)

    sendTxsAndThenPriorityTx(
      i =>
        sender.broadcastInvokeScript(
          caller,
          Recipient().withPublicKeyHash(thirdContractAddr),
          Some(FUNCTION_CALL(FunctionHeader.User("transferAndWrite"), List(Terms.CONST_LONG(i)))),
          fee = invokeFee
        ),
      () => updateAssetScript(result = false, smartAsset, thirdContract, priorityFee)
    ) { txs =>
      val failed              = assertFailedTxs(txs)
      val lastSuccessEndArg   = txs.size - failed.size
      val lastSuccessStartArg = (lastSuccessEndArg - 3).max(1)

      val lastSuccessWrites =
        Range
          .inclusive(lastSuccessStartArg, lastSuccessEndArg)
          .map {
            case i if i % 4 == 0 => "n"  -> IntegerDataEntry("n", i)
            case i if i % 4 == 1 => "b"  -> BooleanDataEntry("b", i % 2 == 0)
            case i if i % 4 == 2 => "bn" -> BinaryDataEntry("bn", ByteStr(Longs.toByteArray(i)))
            case i if i % 4 == 3 => "s"  -> StringDataEntry("s", i.toString)
          }
          .toMap
          .mapValues(PBTransactions.toPBDataEntry)
      initialEntries.map(entry => entry.key -> entry).toMap.foreach {
        case (key, initial) =>
          sender.getDataByKey(thirdContractAddr, key) shouldBe List(lastSuccessWrites.getOrElse(key, initial))
      }
      failed
    }
  }

  test("InvokeScriptTransaction: reject transactions if account script failed") {
    val invokeFee            = 0.005.waves
    val setAssetScriptMinFee = setAssetScriptFee + smartFee * 2
    val priorityFee          = setAssetScriptMinFee + invokeFee

    updateTikTok("unknown", setAssetScriptMinFee)
    updateAssetScript(result = true, smartAsset, thirdContract, setAssetScriptMinFee)

    val prevBalance = sender.wavesBalance(callerAddr).regular

    sendTxsAndThenPriorityTx(
      _ =>
        sender.broadcastInvokeScript(
          caller,
          Recipient().withPublicKeyHash(thirdContractAddr),
          Some(FUNCTION_CALL(FunctionHeader.User("tikTok"), List.empty)),
          fee = invokeFee
        ),
      () =>
        sender.setScript(
          caller,
          Right(
            ScriptCompiler
              .compile(
                s"""
                   |{-# STDLIB_VERSION 3 #-}
                   |{-# CONTENT_TYPE EXPRESSION #-}
                   |{-# SCRIPT_TYPE ACCOUNT #-}
                   |
                   |match (tx) {
                   |case t: InvokeScriptTransaction => false
                   |case _ => true
                   |}
                   |""".stripMargin,
                ScriptEstimatorV3
              )
              .toOption
              .map(_._1)
          ),
          fee = priorityFee,
          waitForTx = true
        )
    ) { txs =>
      val invalid = assertInvalidTxs(txs)
      sender.wavesBalance(callerAddr).regular shouldBe prevBalance - (txs.size - invalid.size) * invokeFee - priorityFee
      invalid
    }
  }

  test("ExchangeTransaction: failed exchange tx when asset script fails") {
    val init = Seq(
      sender.setScript(firstAcc, Right(None), setScriptFee + smartFee),
      sender.setScript(secondAcc, Right(None), setScriptFee + smartFee),
      sender.setScript(thirdAcc, Right(None), setScriptFee + smartFee)
    )

    waitForTxs(init)

    val seller         = firstAcc
    val buyer          = secondAcc
    val matcher        = thirdAcc
    val sellerAddress  = firstAddress
    val buyerAddress   = secondAddress
//    val matcherAddress = thirdAddress

//    val transfers = Seq(
//      sender.broadcastTransfer(sender.privateKey, Recipient().withPublicKeyHash(sellerAddress), 100.waves, minFee),
//      sender.broadcastTransfer(sender.privateKey, Recipient().withPublicKeyHash(buyerAddress), 100.waves, minFee),
//      sender.broadcastTransfer(sender.privateKey, Recipient().withPublicKeyHash(matcherAddress), 100.waves, minFee)
//    )

    val quantity                                        = 1000000000L
    val initScript: Either[Array[Byte], Option[Script]] = Right(ScriptCompiler.compile("true", ScriptEstimatorV3).toOption.map(_._1))
    val amountAsset                                     = sender.broadcastIssue(seller, "Amount asset", quantity, 8, reissuable = true, issueFee, script = initScript)
    val priceAsset                                      = sender.broadcastIssue(buyer, "Price asset", quantity, 8, reissuable = true, issueFee, script = initScript)
    val sellMatcherFeeAsset                             = sender.broadcastIssue(matcher, "Seller fee asset", quantity, 8, reissuable = true, issueFee, script = initScript)
    val buyMatcherFeeAsset                              = sender.broadcastIssue(matcher, "Buyer fee asset", quantity, 8, reissuable = true, issueFee, script = initScript)

    val preconditions = /*transfers ++ */Seq(
      amountAsset,
      priceAsset,
      sellMatcherFeeAsset,
      buyMatcherFeeAsset
    )

    waitForTxs(preconditions)

    val sellMatcherFeeAssetId = PBTransactions.vanillaUnsafe(sellMatcherFeeAsset).id().toString
    val buyMatcherFeeAssetId  = PBTransactions.vanillaUnsafe(buyMatcherFeeAsset).id().toString

    val transferToSeller = sender.broadcastTransfer(
      matcher,
      Recipient().withPublicKeyHash(sellerAddress),
      quantity,
      fee = minFee + smartFee,
      assetId = sellMatcherFeeAssetId
    )
    val transferToBuyer = sender.broadcastTransfer(
      matcher,
      Recipient().withPublicKeyHash(buyerAddress),
      quantity,
      fee = minFee + smartFee,
      assetId = buyMatcherFeeAssetId
    )

    waitForTxs(Seq(transferToSeller, transferToBuyer))

    val amountAssetId  = PBTransactions.vanillaUnsafe(amountAsset).id().toString
    val priceAssetId   = PBTransactions.vanillaUnsafe(priceAsset).id().toString
    val assetPair      = AssetPair.createAssetPair(amountAssetId, priceAssetId).get
    val fee            = 0.003.waves + 4 * smartFee
    val sellMatcherFee = fee / 100000L
    val buyMatcherFee  = fee / 100000L
    val priorityFee    = setAssetScriptFee + smartFee + fee * 10

    val allCases =
      Seq((amountAssetId, seller), (priceAssetId, buyer), (sellMatcherFeeAssetId, matcher), (buyMatcherFeeAssetId, matcher))

    for ((invalidScriptAsset, owner) <- allCases) {
      val txsSend = (_: Int) => {
        val tx = PBTransactions.protobuf(
          mkExchange(buyer, seller, matcher, assetPair, fee, buyMatcherFeeAssetId, sellMatcherFeeAssetId, buyMatcherFee, sellMatcherFee)
        )
        sender.broadcast(tx.transaction.get, tx.proofs)
      }
      sendTxsAndThenPriorityTx(
        txsSend,
        () => updateAssetScript(result = false, invalidScriptAsset, owner, priorityFee)
      )(assertFailedTxs)
      updateAssetScript(result = true, invalidScriptAsset, owner, setAssetScriptFee + smartFee)
    }
  }

  test("ExchangeTransaction: invalid exchange tx when account script fails") {
//    val init = Seq(
//      sender.setScript(firstAcc, Right(None), setScriptFee + smartFee),
//      sender.setScript(secondAcc, Right(None), setScriptFee + smartFee),
//      sender.setScript(thirdAcc, Right(None), setScriptFee + smartFee)
//    )

//    waitForTxs(init)

    val seller         = firstAcc
    val buyer          = secondAcc
    val matcher        = thirdAcc
    val sellerAddress  = firstAddress
    val buyerAddress   = secondAddress
//    val matcherAddress = thirdAddress

   /* val transfers = Seq(
      sender.broadcastTransfer(sender.privateKey, Recipient().withPublicKeyHash(sellerAddress), 100.waves, minFee),
      sender.broadcastTransfer(sender.privateKey, Recipient().withPublicKeyHash(buyerAddress), 100.waves, minFee),
      sender.broadcastTransfer(sender.privateKey, Recipient().withPublicKeyHash(matcherAddress), 100.waves, minFee)
    )*/

    val quantity            = 1000000000L
    val amountAsset         = sender.broadcastIssue(seller, "Amount asset", quantity, 8, reissuable = true, issueFee)
    val priceAsset          = sender.broadcastIssue(buyer, "Price asset", quantity, 8, reissuable = true, issueFee)
    val sellMatcherFeeAsset = sender.broadcastIssue(matcher, "Seller fee asset", quantity, 8, reissuable = true, issueFee)
    val buyMatcherFeeAsset  = sender.broadcastIssue(matcher, "Buyer fee asset", quantity, 8, reissuable = true, issueFee)

    val preconditions = /*transfers ++ */Seq(
      amountAsset,
      priceAsset,
      sellMatcherFeeAsset,
      buyMatcherFeeAsset
    )

    waitForTxs(preconditions)

    val sellMatcherFeeAssetId = PBTransactions.vanillaUnsafe(sellMatcherFeeAsset).id().toString
    val buyMatcherFeeAssetId  = PBTransactions.vanillaUnsafe(buyMatcherFeeAsset).id().toString

    val transferToSeller = sender.broadcastTransfer(
      matcher,
      Recipient().withPublicKeyHash(sellerAddress),
      quantity,
      fee = minFee + smartFee,
      assetId = sellMatcherFeeAssetId
    )
    val transferToBuyer = sender.broadcastTransfer(
      matcher,
      Recipient().withPublicKeyHash(buyerAddress),
      quantity,
      fee = minFee + smartFee,
      assetId = buyMatcherFeeAssetId
    )

    waitForTxs(Seq(transferToSeller, transferToBuyer))

    val amountAssetId  = PBTransactions.vanillaUnsafe(amountAsset).id().toString
    val priceAssetId   = PBTransactions.vanillaUnsafe(priceAsset).id().toString
    val assetPair      = AssetPair.createAssetPair(amountAssetId, priceAssetId).get
    val fee            = 0.003.waves + smartFee
    val sellMatcherFee = fee / 100000L
    val buyMatcherFee  = fee / 100000L
    val priorityFee    = setScriptFee + smartFee + fee * 10

    val allCases = Seq(seller, buyer, matcher)
    allCases.foreach(address => updateAccountScript(None, address, setScriptFee + smartFee))

    for (invalidAccount <- allCases) {
      val txsSend = (_: Int) => {
        val tx = PBTransactions.protobuf(
          mkExchange(buyer, seller, matcher, assetPair, fee, buyMatcherFeeAssetId, sellMatcherFeeAssetId, buyMatcherFee, sellMatcherFee)
        )
        sender.broadcast(tx.transaction.get, tx.proofs)
      }

      sendTxsAndThenPriorityTx(
        txsSend,
        () => updateAccountScript(Some(false), invalidAccount, priorityFee)
      )(assertInvalidTxs)
      updateAccountScript(None, invalidAccount, setScriptFee + smartFee)
    }
  }

  private def updateTikTok(result: String, fee: Long): PBSignedTransaction =
    sender.putData(thirdContract, List(StringDataEntry("tikTok", result)).map(PBTransactions.toPBDataEntry), fee = fee, waitForTx = true)

  private def waitForTxs(txs: Seq[PBSignedTransaction]): Unit = {
    txs.foreach(tx => sender.waitForTransaction(PBTransactions.vanillaUnsafe(tx).id().toString))
  }

  override protected def waitForHeightArise(): Unit = sender.waitForHeight(sender.height + 1)
}