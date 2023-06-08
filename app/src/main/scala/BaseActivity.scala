package wtf.nbd.obw

import java.io.{File, FileOutputStream}
import java.lang.{Integer => JInt}
import java.util.concurrent.TimeUnit

import android.content.pm.PackageManager
import android.content.{DialogInterface, Intent}
import android.graphics.Bitmap.Config.ARGB_8888
import android.graphics.{Bitmap, Color}
import android.net.Uri
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.text.{Editable, Spanned, TextWatcher}
import android.view.View.OnClickListener
import android.view.{View, ViewGroup, WindowManager}
import android.widget._
import androidx.appcompat.app.{AlertDialog, AppCompatActivity}
import androidx.appcompat.widget.AppCompatButton
import androidx.cardview.widget.CardView
import androidx.core.app.ActivityCompat
import androidx.core.content.{ContextCompat, FileProvider}
import androidx.recyclerview.widget.RecyclerView
import wtf.nbd.obw.BaseActivity.StringOps
import wtf.nbd.obw.R
import wtf.nbd.obw.sheets.HasUrDecoder
import com.chauthai.swipereveallayout.SwipeRevealLayout
import com.cottacush.android.currencyedittext.CurrencyEditText
import com.google.android.material.slider.Slider
import com.google.android.material.snackbar.{BaseTransientBottomBar, Snackbar}
import com.google.android.material.textfield.TextInputLayout
import com.google.common.cache.{Cache, CacheBuilder}
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.{BarcodeFormat, EncodeHintType}
import com.guardanis.applock.AppLock
import com.journeyapps.barcodescanner.{BarcodeResult, BarcodeView}
import com.ornach.nobobutton.NoboButton
import com.softwaremill.quicklens._
import com.sparrowwallet.hummingbird.registry.CryptoPSBT
import com.sparrowwallet.hummingbird.{UR, UREncoder}
import fr.acinq.bitcoin._
import fr.acinq.eclair._
import fr.acinq.eclair.blockchain.electrum.ElectrumEclairWallet
import fr.acinq.eclair.blockchain.fee.{FeeratePerByte, FeeratePerKw}
import fr.acinq.eclair.payment.Bolt11Invoice
import fr.acinq.eclair.transactions.Transactions
import fr.acinq.eclair.wire.ChannelReestablish
import immortan._
import immortan.crypto.Tools._
import immortan.utils._
import org.apmem.tools.layouts.FlowLayout
import rx.lang.scala.{Observable, Subscription}
import scodec.bits.ByteVector

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

object BaseActivity {
  implicit class StringOps(source: String) {
    def html: Spanned = android.text.Html.fromHtml(source)
    def humanFour: String = source.grouped(4).mkString(s"\u0020")

    def short: String = {
      val secondFirst = source.slice(4, 8)
      val doubleSmall = "<sup><small><small>&#8230;</small></small></sup>"
      s"${source take 4}&#160;$secondFirst&#160;$doubleSmall&#160;${source takeRight 4}"
    }
  }

  def totalBalance: MilliSatoshi = {
    val chainBalances = LNParams.chainWallets.wallets.map(_.getData.balance)
    Channel.totalBalance(LNParams.cm.all.values) + chainBalances.sum
  }
}

trait ExternalDataChecker {
  def checkExternalData(onNothing: Runnable): Unit
  val noneRunnable: Runnable = new Runnable { def run(): Unit = none }
}

trait ChoiceReceiver {
  def onChoiceMade(tag: AnyRef, pos: Int): Unit
}

trait BaseActivity extends AppCompatActivity { self =>
  lazy val qrSize: Int = getResources.getDimensionPixelSize(R.dimen.qr_size)
  val nothingUsefulTask: Runnable = UITask(
    WalletApp.app.quickToast(R.string.error_nothing_useful)
  )
  val timer: java.util.Timer = new java.util.Timer

  def goBack(view: View): Unit = finish

  val goTo: Class[_] => Any = target => {
    startActivity(new Intent(this, target))
    InputParser.DoNotEraseRecordedValue
  }

  def goToWithValue(target: Class[_], value: Any): Any = {
    // Utility method in case if target expects a value
    InputParser.value = value
    goTo(target)
  }

  val exitTo: Class[_] => Any = target => {
    startActivity(new Intent(this, target))
    runAnd(InputParser.DoNotEraseRecordedValue)(finish)
  }

  def START(state: Bundle): Unit

  override def onCreate(savedActivityState: Bundle): Unit = {
    Thread setDefaultUncaughtExceptionHandler new UncaughtHandler(this)
    super.onCreate(savedActivityState)
    START(savedActivityState)
  }

  override def onDestroy(): Unit = {
    super.onDestroy
    timer.cancel
  }

  // Helpers
  def bringChainWalletChooser(
      title: TitleView
  )(onWalletSelected: ElectrumEclairWallet => Unit): Unit = {
    val cardsContainer = getLayoutInflater
      .inflate(R.layout.frag_linear_layout, null)
      .asInstanceOf[LinearLayout]
    val alert = mkCheckForm(
      _.dismiss,
      none,
      titleBodyAsViewBuilder(title.view, cardsContainer),
      R.string.dialog_cancel,
      -1
    )
    addFlowChip(
      title.flow,
      getString(R.string.choose_wallet),
      R.drawable.border_yellow
    )

    val chooser: ChainWalletCards = new ChainWalletCards(this) {
      override def onWalletTap(wallet: ElectrumEclairWallet): Unit = {
        onWalletSelected(wallet)
        alert.dismiss
      }

      override def onLabelTap(wallet: ElectrumEclairWallet): Unit =
        alert.dismiss
      override def onRemoveTap(wallet: ElectrumEclairWallet): Unit =
        alert.dismiss
      override def onCoinControlTap(wallet: ElectrumEclairWallet): Unit =
        alert.dismiss
      val holder: LinearLayout = cardsContainer
    }

    chooser.init(LNParams.chainWallets.usableWallets)
    chooser.update(LNParams.chainWallets.usableWallets)
    chooser.unPad(LNParams.chainWallets.usableWallets)
  }

  def titleViewFromUri(uri: BitcoinUri): TitleView = {
    val label =
      uri.label.map(label => s"<br><br><b>$label</b>").getOrElse(new String)
    val message =
      uri.message.map(message => s"<br><i>$message<i>").getOrElse(new String)
    val caption =
      getString(R.string.dialog_send_btc)
        .format(uri.address.short, label + message)
    val title = new TitleView(caption)

    for (amount <- uri.amount) {
      val amountHuman = WalletApp.denom.parsedWithSign(amount)
      val requested =
        getString(R.string.dialog_ln_requested).format(amountHuman)
      addFlowChip(title.flow, requested, R.drawable.border_yellow)
    }

    title
  }

  def chainWalletBackground(wallet: ElectrumEclairWallet): Int =
    if (wallet.isBuiltIn) R.color.veryDarkGrey else R.color.veryDarkGrey

  def chainWalletNotice(wallet: ElectrumEclairWallet): Option[Int] =
    if (wallet.hasFingerprint) Some(R.string.hardware_wallet)
    else if (!wallet.isSigning) Some(R.string.watching_wallet)
    else None

  def browse(maybeUri: String): Unit =
    try
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(maybeUri)))
    catch { case exception: Throwable => onFail(exception) }

  def share(text: CharSequence): Unit = startActivity {
    val shareAction = (new Intent).setAction(Intent.ACTION_SEND)
    shareAction.setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
  }

  def viewRecoveryCode(): Unit = {
    val content = new TitleView(
      getString(R.string.settings_view_revocery_phrase_ext)
    )
    getWindow.setFlags(
      WindowManager.LayoutParams.FLAG_SECURE,
      WindowManager.LayoutParams.FLAG_SECURE
    )
    new AlertDialog.Builder(this, R.style.DialogTheme)
      .setView(content.asDefView)
      .show setOnDismissListener new DialogInterface.OnDismissListener {
      override def onDismiss(dialog: DialogInterface): Unit =
        getWindow.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    for (
      mnemonicWord ~ mnemonicIndex <- LNParams.secret.mnemonic.zipWithIndex
    ) {
      val item = s"${mnemonicIndex + 1} $mnemonicWord"
      addFlowChip(content.flow, item, R.drawable.border_green, _ => {})
    }
  }

  // Snackbar
  def snack(parent: View, msg: CharSequence, res: Int): Try[Snackbar] = Try {
    val snack: Snackbar =
      Snackbar.make(parent, msg, BaseTransientBottomBar.LENGTH_INDEFINITE)
    snack.getView
      .findViewById(com.google.android.material.R.id.snackbar_text)
      .asInstanceOf[TextView]
      .setMaxLines(5)
    snack
  }

  def snack(
      parent: View,
      msg: CharSequence,
      res: Int,
      fun: Snackbar => Unit
  ): Try[Snackbar] = snack(parent, msg, res).map { snack =>
    val listener = onButtonTap(fun(snack))
    snack.setAction(res, listener).show
    snack
  }

  // Listener helpers
  def onButtonTap(fun: => Unit): OnClickListener = new OnClickListener {
    def onClick(view: View): Unit = fun
  }

  def onTextChange(fun: String => Unit): TextWatcher = new TextWatcher {
    override def onTextChanged(c: CharSequence, x: Int, y: Int, z: Int): Unit =
      fun(c.toString)
    override def beforeTextChanged(
        s: CharSequence,
        x: Int,
        y: Int,
        z: Int
    ): Unit = none
    override def afterTextChanged(e: Editable): Unit = none
  }

  def runInFutureProcessOnUI[T](fun: => T, no: Throwable => Unit)(
      ok: T => Unit
  ): Unit = runFutureProcessOnUI[T](Future(fun), no)(ok)

  def runFutureProcessOnUI[T](fun: Future[T], no: Throwable => Unit)(
      ok: T => Unit
  ): Unit = fun.onComplete {
    case Success(result) => UITask(ok(result)).run
    case Failure(error)  => UITask(no(error)).run
  }

  def setVis(isVisible: Boolean, view: View): Unit = {
    val nextMode = if (isVisible) View.VISIBLE else View.GONE
    if (view.getVisibility != nextMode) view.setVisibility(nextMode)
  }

  def setVisMany(items: (Boolean, View)*): Unit = {
    for (isVisible ~ view <- items) setVis(isVisible, view)
  }

  def isVisible(view: View): Boolean = view.getVisibility == View.VISIBLE

  def runDelayed(ms: Int)(fun: => Any): Unit =
    timer.schedule(UITask(fun), ms.toLong)

  def UITask(fun: => Any): java.util.TimerTask = {
    val runnableExec = new Runnable { override def run(): Unit = fun }
    new java.util.TimerTask { def run(): Unit = runOnUiThread(runnableExec) }
  }

  def selectorList(listAdapter: ListAdapter): ListView = {
    val list = getLayoutInflater
      .inflate(R.layout.frag_selector_list, null)
      .asInstanceOf[ListView]
    list.setAdapter(listAdapter)
    list
  }

  // Builders
  def clickableTextField(view: View): TextView = {
    val field: TextView = view.asInstanceOf[TextView]
    field setMovementMethod LinkMovementMethod.getInstance
    field
  }

  def titleBodyAsViewBuilder(title: View, body: View): AlertDialog.Builder =
    new AlertDialog.Builder(this, R.style.DialogTheme)
      .setCustomTitle(title)
      .setView(body)

  def onFail(error: String): Unit = UITask {
    val bld = titleBodyAsViewBuilder(null, error.asDefView)
    val bld1 = bld.setPositiveButton(R.string.dialog_ok, null)
    showForm(bld1.create)
  }.run

  def onFail(error: Throwable): Unit = error match {
    case exc if exc.getCause.isInstanceOf[java.io.InterruptedIOException] =>
    case _ => {
      error.printStackTrace()
      onFail(error.toString)
    }
  }

  def getPositiveButton(alert: AlertDialog): Button =
    alert.getButton(DialogInterface.BUTTON_POSITIVE)

  def getNegativeButton(alert: AlertDialog): Button =
    alert.getButton(DialogInterface.BUTTON_NEGATIVE)

  def getNeutralButton(alert: AlertDialog): Button =
    alert.getButton(DialogInterface.BUTTON_NEUTRAL)

  def mkCheckForm(
      ok: AlertDialog => Unit,
      no: => Unit,
      bld: AlertDialog.Builder,
      okRes: Int,
      noRes: Int
  ): AlertDialog = {
    // Create alert dialog where NEGATIVE button removes a dialog AND calls a respected provided function
    // both POSITIVE and NEGATIVE buttons may be omitted by providing -1 as their resource ids
    if (-1 != noRes) bld.setNegativeButton(noRes, null)
    if (-1 != okRes) bld.setPositiveButton(okRes, null)
    val alert = showForm(bld.create)

    val posAct = onButtonTap {
      ok(alert)
    }

    val negAct = onButtonTap {
      alert.dismiss
      no
    }

    if (-1 != noRes) getNegativeButton(alert) setOnClickListener negAct
    if (-1 != okRes) getPositiveButton(alert) setOnClickListener posAct
    alert
  }

  def mkCheckFormNeutral(
      ok: AlertDialog => Unit,
      no: => Unit,
      neutral: AlertDialog => Unit,
      bld: AlertDialog.Builder,
      okRes: Int,
      noRes: Int,
      neutralRes: Int
  ): AlertDialog = {
    if (-1 != neutralRes) bld.setNeutralButton(neutralRes, null)
    val alert = mkCheckForm(ok, no, bld, okRes, noRes)

    val neutralAct = onButtonTap {
      neutral(alert)
    }

    // Extend base dialog with a special NEUTRAL button, may be omitted by providing -1
    if (-1 != neutralRes) getNeutralButton(alert) setOnClickListener neutralAct
    alert
  }

  def showForm(alertDialog: AlertDialog): AlertDialog = {
    // Display dialog taking into account that host activity may not be there
    // Popup window is not cancellable on touch outside and may be width-limited

    try {
      alertDialog.show
      // First, limit popup window width in case if this is a tablet, then make sure it does not blow up if called on destroyed activity
      if (WalletApp.app.scrWidth > 2.3)
        alertDialog.getWindow.setLayout(
          WalletApp.app.maxDialog.toInt,
          ViewGroup.LayoutParams.WRAP_CONTENT
        )
      clickableTextField(alertDialog findViewById android.R.id.message)
      alertDialog.setCanceledOnTouchOutside(false)
    } catch none
    alertDialog
  }

  // Scanner
  final val scannerRequestCode = 101

  def callScanner(sheet: sheets.ScannerBottomSheet): Unit = {
    val allowed = ContextCompat.checkSelfPermission(
      this,
      android.Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED
    if (!allowed)
      ActivityCompat.requestPermissions(
        this,
        Array(android.Manifest.permission.CAMERA),
        scannerRequestCode
      )
    else sheet.show(getSupportFragmentManager, "scanner-bottom-sheet-fragment")
  }

  def addFlowChip(
      flow: FlowLayout,
      chipText: String,
      backgroundRes: Int,
      shareText: Option[String] = None
  ): TextView = {
    def copyText(defText: String): Unit =
      WalletApp.app.copy(shareText getOrElse defText)
    addFlowChip(flow, chipText, backgroundRes, copyText _)
  }

  def addFlowChip(
      flow: FlowLayout,
      chipText: String,
      backgroundRes: Int,
      onTap: String => Unit
  ): TextView = {
    val text = getLayoutInflater
      .inflate(R.layout.frag_chip_text, flow, false)
      .asInstanceOf[TextView]
    text.setOnClickListener(onButtonTap(onTap(text.getText.toString)))
    text.setBackgroundResource(backgroundRes)
    flow.setVisibility(View.VISIBLE)
    text.setText(chipText.html)
    flow.addView(text)
    text
  }

  def showKeys(input: EditText): Unit = {
    // Popup forms can't show keyboard immediately due to animation, so delay it a bit
    def process(): Unit =
      runAnd(input.requestFocus)(WalletApp.app showKeys input)
    timer.schedule(UITask(process()), 225)
  }

  def singleInputPopup: (View, TextInputLayout, EditText) = {
    val container: View =
      getLayoutInflater.inflate(R.layout.frag_hint_input, null, false)
    val extraInputLayout = container
      .findViewById(R.id.extraInputLayout)
      .asInstanceOf[TextInputLayout]
    val extraInput =
      container.findViewById(R.id.extraInput).asInstanceOf[EditText]
    (container, extraInputLayout, extraInput)
  }

  // Rich popup title
  implicit class TitleView(titleText: String) {
    val view: LinearLayout = getLayoutInflater
      .inflate(R.layout.frag_top_tip, null)
      .asInstanceOf[LinearLayout]
    val flow: FlowLayout =
      view.findViewById(R.id.tipExtraTags).asInstanceOf[FlowLayout]
    val tipTitle: TextView = clickableTextField(
      view.findViewById(R.id.tipTitle)
    )
    tipTitle.setText(titleText.html)

    def asDefView: LinearLayout = {
      view.setBackgroundColor(0x22aaaaaa)
      view
    }

    def asColoredView(colorRes: Int): LinearLayout = {
      val resultColor = ContextCompat.getColor(self, colorRes)
      view.setBackgroundColor(resultColor)
      view
    }
  }

  // Fiat / BTC converter
  def updatePopupButton(button: Button, isEnabled: Boolean): Unit = {
    val alpha = if (isEnabled) 1f else 0.3f
    button.setEnabled(isEnabled)
    button.setAlpha(alpha)
  }

  class RateManager(
      val content: ViewGroup,
      extraText: Option[String],
      visHintRes: Int,
      rates: Fiat2Btc,
      fiatCode: String
  ) {
    val fiatInputAmount: CurrencyEditText =
      content.findViewById(R.id.fiatInputAmount).asInstanceOf[CurrencyEditText]
    val inputAmount: CurrencyEditText =
      content.findViewById(R.id.inputAmount).asInstanceOf[CurrencyEditText]

    val fiatInputAmountHint: TextView =
      content.findViewById(R.id.fiatInputAmountHint).asInstanceOf[TextView]
    val inputAmountHint: TextView =
      content.findViewById(R.id.inputAmountHint).asInstanceOf[TextView]

    val hintFiatDenom: TextView = clickableTextField(
      content findViewById R.id.hintFiatDenom
    )
    val hintDenom: TextView = clickableTextField(
      content findViewById R.id.hintDenom
    )

    val extraInputOption: TextView =
      content.findViewById(R.id.extraInputOption).asInstanceOf[TextView]
    val extraInputVisibility: TextView =
      content.findViewById(R.id.extraInputVisibility).asInstanceOf[TextView]
    val extraInputLayout: TextInputLayout =
      content.findViewById(R.id.extraInputLayout).asInstanceOf[TextInputLayout]
    val extraInput: EditText =
      content.findViewById(R.id.extraInput).asInstanceOf[EditText]

    val attachIdentity: CheckBox =
      content.findViewById(R.id.attachIdentity).asInstanceOf[CheckBox]
    val holdPayment: CheckBox =
      content.findViewById(R.id.holdPayment).asInstanceOf[CheckBox]

    def updateText(value: MilliSatoshi): Unit = {
      val amount = WalletApp.denom.fromMsat(value).toString
      runAnd(inputAmount.requestFocus)(inputAmount.setText(amount))
      updateFiatInput()
    }
    def bigDecimalFrom(input: CurrencyEditText): BigDecimal = BigDecimal(
      input.getNumericValueBigDecimal
    )
    def resultExtraInput: Option[String] =
      Option(extraInput.getText.toString).map(trimmed).filter(_.nonEmpty)
    def resultMsat: MilliSatoshi =
      (bigDecimalFrom(inputAmount) * WalletApp.denom.factor).toLong.msat

    def updatedFiatFromBtc: String =
      WalletApp
        .msatInFiat(rates, fiatCode)(resultMsat)
        .filter(0.001d <= _)
        .map(_.toString)
        .getOrElse("")

    def updatedBtcFromFiat: String =
      WalletApp
        .currentRate(rates, fiatCode)
        .map(perBtc => bigDecimalFrom(fiatInputAmount) / perBtc)
        .filter(0.000000001d <= _)
        .map(Denomination.btcBigDecimal2MSat)
        .map(msat =>
          // round so the final result doesn't have millisatoshi units
          MilliSatoshi((msat.underlying.toDouble / 1000).round * 1000)
        )
        .map(WalletApp.denom.fromMsat)
        .map(_.toString)
        .getOrElse("")

    def updateFiatInput(): Unit = {
      fiatInputAmount.setText(updatedFiatFromBtc)
      fiatInputAmount.setMaxNumberOfDecimalDigits(2)
    }

    def updateBtcInput(): Unit = {
      inputAmount.setText(updatedBtcFromFiat)
      inputAmount.setMaxNumberOfDecimalDigits(8)
    }

    extraText match {
      case Some(hintText) =>
        val revealExtraInputListener = onButtonTap {
          extraInputLayout.setVisibility(View.VISIBLE)
          extraInputOption.setVisibility(View.GONE)
          showKeys(extraInput)
        }

        extraInputLayout.setHint(hintText)
        extraInputOption.setText(hintText)
        extraInputVisibility.setText(visHintRes)
        extraInputOption.setOnClickListener(revealExtraInputListener)
        extraInputVisibility.setOnClickListener(revealExtraInputListener)

      case None =>
        extraInputOption.setVisibility(View.GONE)
        extraInputVisibility.setVisibility(View.GONE)
    }

    fiatInputAmount.addTextChangedListener(onTextChange { _ =>
      if (fiatInputAmount.hasFocus) updateBtcInput()
    })
    inputAmount.addTextChangedListener(onTextChange { _ =>
      if (inputAmount.hasFocus) updateFiatInput()
    })
    inputAmountHint.setText(WalletApp.denom.sign.toUpperCase)
    fiatInputAmountHint.setText(fiatCode.toUpperCase)
    fiatInputAmount.setLocale(Denomination.locale)
    inputAmount.setLocale(Denomination.locale)
  }

  class TwoSidedItem(
      val parent: View,
      firstText: CharSequence,
      secondText: CharSequence
  ) {
    val secondItem: TextView =
      parent.findViewById(R.id.secondItem).asInstanceOf[TextView]
    val firstItem: TextView =
      parent.findViewById(R.id.firstItem).asInstanceOf[TextView]
    secondItem.setText(secondText)
    firstItem.setText(firstText)
  }

  abstract class FeeView(from: FeeratePerByte, val content: View) {
    val feeRate: TextView =
      content.findViewById(R.id.feeRate).asInstanceOf[TextView]
    val txIssues: TextView =
      content.findViewById(R.id.txIssues).asInstanceOf[TextView]
    val bitcoinFee: TextView =
      content.findViewById(R.id.bitcoinFee).asInstanceOf[TextView]
    val fiatFee: TextView =
      content.findViewById(R.id.fiatFee).asInstanceOf[TextView]

    val customFeerate: Slider =
      content.findViewById(R.id.customFeerate).asInstanceOf[Slider]
    val customFeerateOption: TextView =
      content.findViewById(R.id.customFeerateOption).asInstanceOf[TextView]
    var rate: FeeratePerKw = _

    val onChange: Unit => Unit
    def update(feeOpt: Option[MilliSatoshi], showIssue: Boolean): Unit = {
      feeRate.setText(
        getString(R.string.dialog_fee_sat_vbyte)
          .format(FeeratePerByte(rate).feerate.toLong)
          .html
      )

      setVisMany(
        feeOpt.isDefined -> bitcoinFee,
        feeOpt.isDefined -> fiatFee,
        showIssue -> txIssues
      )

      feeOpt.foreach { fee =>
        val humanFee =
          WalletApp.denom.parsedWithSign(fee).html
        fiatFee.setText(WalletApp.currentMsatInFiatHuman(fee).html)
        bitcoinFee.setText(humanFee)
      }
    }

    private val revealSlider = onButtonTap {
      val currentFeerate = FeeratePerByte(rate).feerate.toLong
      customFeerate.setValueFrom(from.feerate.toLong.toFloat)
      customFeerate.setValueTo((currentFeerate * 10).toFloat)
      customFeerate.setValue(currentFeerate.toFloat)

      customFeerateOption.setVisibility(View.GONE)
      customFeerate.setVisibility(View.VISIBLE)
    }

    customFeerateOption.setOnClickListener(revealSlider)
    feeRate.setOnClickListener(revealSlider)

    customFeerate.addOnChangeListener(new Slider.OnChangeListener {
      override def onValueChange(
          slider: Slider,
          value: Float,
          fromUser: Boolean
      ): Unit = {
        val feeratePerByte = FeeratePerByte(value.toLong.sat)
        rate = FeeratePerKw(feeratePerByte)
        onChange(())
      }
    })
  }

  // Chan TX popup for signing and hardware wallets
  class ChainButtonsView(host: View) {
    val chainText: TextView =
      host.findViewById(R.id.chainText).asInstanceOf[TextView]
    val chainNextButton: NoboButton =
      host.findViewById(R.id.chainNextButton).asInstanceOf[NoboButton]
    val chainEditButton: NoboButton =
      host.findViewById(R.id.chainEditButton).asInstanceOf[NoboButton]
    val chainCancelButton: NoboButton =
      host.findViewById(R.id.chainCancelButton).asInstanceOf[NoboButton]
  }

  sealed trait HasHostView {
    val host: View
  }

  class ChainSlideshowView(val host: View) extends HasHostView {
    val chainButtonsView: ChainButtonsView = new ChainButtonsView(host)
    val qrSlideshow: ImageView =
      host.findViewById(R.id.qrSlideshow).asInstanceOf[ImageView]
    var subscription: Option[Subscription] = None

    def activate(psbt: Psbt): Unit = {
      val encoder =
        new UREncoder(new CryptoPSBT(Psbt write psbt).toUR, 200, 50, 0)
      val stringToQr: String => Bitmap = sourceChunk =>
        QRActivity.get(sourceChunk.toUpperCase, qrSize)
      val updateView: Bitmap => Unit = sourceQrCode =>
        UITask(qrSlideshow setImageBitmap sourceQrCode).run
      subscription = Some(
        Observable
          .interval(0.second, 600.millis)
          .map(_ => encoder.nextPart)
          .map(stringToQr)
          .subscribe(updateView)
      )
    }
  }

  class ChainReaderView(val host: View) extends HasUrDecoder with HasHostView {
    val chainButtonsView: ChainButtonsView = new ChainButtonsView(host)
    var onSignedTx: Transaction => Unit = none

    def stop(): Unit = runAnd(barcodeReader.pause)(barcodeReader.stopDecoding)
    def start(): Unit =
      runAnd(barcodeReader.decodeContinuous(this))(barcodeReader.resume)
    override def barcodeResult(barcodeResult: BarcodeResult): Unit = handleUR(
      barcodeResult.getText
    )
    override def onError(qrParsingError: String): Unit =
      runAnd(stop())(onFail(qrParsingError))
    barcodeReader = host.findViewById(R.id.qrReader).asInstanceOf[BarcodeView]
    instruction = chainButtonsView.chainText

    override def onUR(ur: UR): Unit = {
      val tx = Try({
        val rawPsbt = ur.decodeFromRegistry.asInstanceOf[CryptoPSBT]
        ByteVector.view(rawPsbt.getPsbt)
      })
        .flatMap(Psbt.read)
        .flatMap(extractBip84Tx)

      tx match {
        case Success(signedTx) => onSignedTx(signedTx)
        case Failure(why)      => onError(why.stackTraceAsString)
      }
    }
  }

  class ChainConfirmView(val host: View) extends HasHostView {
    val chainButtonsView: ChainButtonsView = new ChainButtonsView(host)
    val confirmFiat = new TwoSidedItem(
      host.findViewById(R.id.confirmFiat),
      getString(R.string.dialog_send_btc_confirm_fiat),
      new String
    )
    val confirmAmount = new TwoSidedItem(
      host.findViewById(R.id.confirmAmount),
      getString(R.string.dialog_send_btc_confirm_amount),
      new String
    )
    val confirmFee = new TwoSidedItem(
      host.findViewById(R.id.confirmFee),
      getString(R.string.dialog_send_btc_confirm_fee),
      new String
    )
  }

  class ChainEditView(
      val host: LinearLayout,
      manager: RateManager,
      fromWallet: ElectrumEclairWallet
  ) extends HasHostView {
    val canSend: String = WalletApp.denom.parsedWithSign(
      fromWallet.getData.balance.toMilliSatoshi
    )
    val canSendFiat: String = WalletApp.currentMsatInFiatHuman(
      fromWallet.getData.balance.toMilliSatoshi
    )
    val inputChain: LinearLayout =
      host.findViewById(R.id.inputChain).asInstanceOf[LinearLayout]
    manager.hintFiatDenom setText getString(R.string.dialog_up_to)
      .format(canSendFiat)
      .html
    manager.hintDenom setText getString(R.string.dialog_up_to)
      .format(canSend)
      .html
  }

  class CircularSpinnerView(val host: View) extends HasHostView

  class ChainSendView(
      val fromWallet: ElectrumEclairWallet,
      badge: Option[String],
      visibilityRes: Int
  ) {
    val body: ScrollView = getLayoutInflater
      .inflate(R.layout.frag_input_on_chain, null)
      .asInstanceOf[ScrollView]
    val manager: RateManager = new RateManager(
      body,
      badge,
      visibilityRes,
      LNParams.fiatRates.info.rates,
      WalletApp.fiatCode
    )
    val chainEditView = new ChainEditView(
      body.findViewById(R.id.editChain).asInstanceOf[LinearLayout],
      manager,
      fromWallet
    )
    lazy val chainSlideshowView = new ChainSlideshowView(
      body findViewById R.id.slideshowChain
    )
    lazy val circularSpinnerView = new CircularSpinnerView(
      body findViewById R.id.progressBar
    )
    lazy val chainConfirmView = new ChainConfirmView(
      body findViewById R.id.confirmChain
    )
    lazy val chainReaderView = new ChainReaderView(
      body findViewById R.id.readerChain
    )

    body post UITask {
      val layoutParams =
        new LinearLayout.LayoutParams(body.getWidth, body.getWidth)
      chainSlideshowView.qrSlideshow.setLayoutParams(layoutParams)
      chainReaderView.barcodeReader.setLayoutParams(layoutParams)
      circularSpinnerView.host.setLayoutParams(layoutParams)
    }

    lazy private val views = List(
      chainEditView,
      chainSlideshowView,
      circularSpinnerView,
      chainConfirmView,
      chainReaderView
    )
    def switchTo(visibleSection: HasHostView): Unit = for (section <- views)
      setVis(isVisible = section == visibleSection, section.host)
    def switchButtons(alert: AlertDialog, on: Boolean): Unit = setVisMany(
      on -> getPositiveButton(alert),
      on -> getNegativeButton(alert),
      on -> getNeutralButton(alert),
      true -> body
    )
    val onDismissListener: DialogInterface.OnDismissListener =
      new DialogInterface.OnDismissListener {
        override def onDismiss(dialog: DialogInterface): Unit = haltProcesses()
      }

    def haltProcesses(): Unit = {
      // Destroy all running processes to not left them hanging
      for (sub <- chainSlideshowView.subscription) sub.unsubscribe()
      chainReaderView.stop()
    }

    def switchToEdit(alert: AlertDialog): Unit = {
      switchButtons(alert, on = true)
      switchTo(chainEditView)
      haltProcesses()
    }

    def switchToConfirm(
        alert: AlertDialog,
        totalAmount: MilliSatoshi,
        fee: MilliSatoshi
    ): Unit = {
      chainConfirmView.chainButtonsView.chainCancelButton setOnClickListener onButtonTap(
        alert.dismiss
      )
      chainConfirmView.chainButtonsView.chainEditButton.setOnClickListener(
        onButtonTap(
          switchToEdit(alert)
        )
      )
      chainConfirmView.confirmAmount.secondItem.setText(
        WalletApp.denom
          .parsedWithSign(totalAmount)
          .html
      )
      chainConfirmView.confirmFee.secondItem.setText(
        WalletApp.denom
          .parsedWithSign(fee)
          .html
      )
      chainConfirmView.confirmFiat.secondItem.setText(
        WalletApp
          .currentMsatInFiatHuman(totalAmount)
          .html
      )
      switchButtons(alert, on = false)
      switchTo(chainConfirmView)
      haltProcesses()
    }

    def switchToHardwareOutgoing(alert: AlertDialog, psbt: Psbt): Unit = {
      chainSlideshowView.chainButtonsView.chainNextButton.setOnClickListener(
        onButtonTap(
          switchToHardwareIncoming(alert)
        )
      )
      chainSlideshowView.chainButtonsView.chainEditButton.setOnClickListener(
        onButtonTap(
          switchToEdit(alert)
        )
      )
      chainSlideshowView.chainButtonsView.chainCancelButton.setOnClickListener(
        onButtonTap(
          alert.dismiss
        )
      )
      chainSlideshowView.activate(psbt)
      switchButtons(alert, on = false)
      switchTo(chainSlideshowView)
      chainReaderView.stop()
    }

    def switchToHardwareIncoming(alert: AlertDialog): Unit = {
      chainReaderView.chainButtonsView.chainCancelButton.setOnClickListener(
        onButtonTap(
          alert.dismiss
        )
      )
      chainReaderView.chainButtonsView.chainEditButton.setOnClickListener(
        onButtonTap(
          switchToEdit(alert)
        )
      )
      setVis(
        isVisible = false,
        chainReaderView.chainButtonsView.chainNextButton
      )
      for (sub <- chainSlideshowView.subscription) sub.unsubscribe()
      switchButtons(alert, on = false)
      switchTo(chainReaderView)
      chainReaderView.start()
    }

    def switchToSpinner(alert: AlertDialog): Unit = {
      switchButtons(alert, on = false)
      switchTo(circularSpinnerView)
      haltProcesses()
    }
  }

  // Guards and send/receive helpers
  def lnSendGuard(prExt: PaymentRequestExt, container: View)(
      onOK: Option[MilliSatoshi] => Unit
  ): Unit = LNParams.cm.checkIfSendable(prExt.pr.paymentHash) match {
    case PaymentInfo.NotSendableInFlight =>
      snack(
        container,
        getString(R.string.error_ln_send_in_flight).html,
        R.string.dialog_ok,
        _.dismiss
      )
    case PaymentInfo.NotSendableSuccess =>
      snack(
        container,
        getString(R.string.error_ln_send_done_already).html,
        R.string.dialog_ok,
        _.dismiss
      )
    case _ if !LNParams.cm.all.values.exists(Channel.isOperationalOrWaiting) =>
      snack(
        container,
        getString(R.string.error_ln_no_chans).html,
        R.string.dialog_ok,
        _.dismiss
      )
    case _ if !prExt.pr.features.hasFeature(Features.PaymentSecret) =>
      snack(
        container,
        getString(R.string.error_ln_send_no_secret).html,
        R.string.dialog_ok,
        _.dismiss
      )
    case _ if !LNParams.cm.all.values.exists(Channel.isOperational) =>
      snack(
        container,
        getString(R.string.error_ln_waiting).html,
        R.string.dialog_ok,
        _.dismiss
      )

    case _
        if LNParams.cm
          .operationalCncs(LNParams.cm.all.values)
          .maxBy(_.commits.availableForSend)
          .commits
          .availableForSend < LNParams.minPayment =>
      snack(
        container,
        getString(R.string.error_ln_send_reserve).html,
        R.string.dialog_ok,
        _.dismiss
      )

    case _ if prExt.pr.amountOpt.exists(_ < LNParams.minPayment) =>
      val requestedHuman =
        WalletApp.denom.parsedWithSign(prExt.pr.amountOpt.get)
      val minHuman =
        WalletApp.denom.parsedWithSign(LNParams.minPayment)
      val msg =
        getString(R.string.error_ln_send_small)
          .format(requestedHuman, minHuman)
          .html
      snack(container, msg, R.string.dialog_ok, _.dismiss)

    case _ if prExt.hasSplitIssue =>
      snack(
        container,
        getString(R.string.error_ln_send_split).html,
        R.string.dialog_ok,
        _.dismiss
      )
    case _ if prExt.pr.isExpired() =>
      snack(
        container,
        getString(R.string.error_ln_send_expired).html,
        R.string.dialog_ok,
        _.dismiss
      )
    case _ if prExt.pr.prefix != Bolt11Invoice.prefixes(LNParams.chainHash) =>
      snack(
        container,
        getString(R.string.error_ln_send_network).html,
        R.string.dialog_ok,
        _.dismiss
      )
    case _ => onOK(prExt.pr.amountOpt)
  }

  def lnReceiveGuard(into: Iterable[Channel], container: View)(
      onOk: => Unit
  ): Unit = LNParams.cm.sortedReceivable(into).lastOption match {
    case _ if !into.exists(Channel.isOperationalOrWaiting) =>
      snack(
        container,
        getString(R.string.error_ln_no_chans).html,
        R.string.dialog_ok,
        _.dismiss
      )
    case _ if !into.exists(Channel.isOperational) =>
      snack(
        container,
        getString(R.string.error_ln_waiting).html,
        R.string.dialog_ok,
        _.dismiss
      )
    case None =>
      snack(
        container,
        getString(R.string.error_ln_receive_no_update).html,
        R.string.dialog_ok,
        _.dismiss
      )

    case Some(cnc) =>
      if (cnc.commits.availableForReceive < 0L.msat) {
        val reservePlusMinPayment =
          cnc.commits.availableForReceive + LNParams.minPayment
        val reserveHuman = WalletApp.denom.parsedWithSign(
          -reservePlusMinPayment
        )
        snack(
          container,
          getString(R.string.error_ln_receive_reserve)
            .format(reserveHuman)
            .html,
          R.string.dialog_ok,
          _.dismiss
        )
      } else onOk
  }

  abstract class OffChainSender(
      val maxSendable: MilliSatoshi,
      val minSendable: MilliSatoshi
  ) extends HasTypicalChainFee {
    val body: android.view.ViewGroup = getLayoutInflater
      .inflate(R.layout.frag_input_off_chain, null)
      .asInstanceOf[android.view.ViewGroup]
    lazy val manager = new RateManager(
      body,
      Some(getString(R.string.dialog_set_label)),
      R.string.dialog_visibility_private,
      LNParams.fiatRates.info.rates,
      WalletApp.fiatCode
    )
    val alert: AlertDialog

    val canSendFiatHuman: String = WalletApp.currentMsatInFiatHuman(maxSendable)
    val canSendHuman: String =
      WalletApp.denom.parsedWithSign(maxSendable)
    manager.hintFiatDenom.setText(
      getString(R.string.dialog_up_to).format(canSendFiatHuman).html
    )
    manager.hintDenom.setText(
      getString(R.string.dialog_up_to).format(canSendHuman).html
    )

    manager.inputAmount.addTextChangedListener(onTextChange { _ =>
      updatePopupButton(getNeutralButton(alert), isNeutralEnabled)
      updatePopupButton(getPositiveButton(alert), isPayEnabled)
    })

    def neutral(alert: AlertDialog): Unit
    def send(alert: AlertDialog): Unit
    def isNeutralEnabled: Boolean
    def isPayEnabled: Boolean

    def baseSendNow(prExt: PaymentRequestExt, alert: AlertDialog): Unit = {
      val cmd = LNParams.cm
        .makeSendCmd(
          prExt,
          LNParams.cm.all.values.toList,
          LNParams.cm.feeReserve(manager.resultMsat),
          manager.resultMsat
        )
        .modify(_.split.totalSum)
        .setTo(manager.resultMsat)
      val pd = PaymentDescription(
        split = None,
        label = manager.resultExtraInput,
        semanticOrder = None,
        invoiceText = prExt.descriptionOpt getOrElse new String
      )
      replaceOutgoingPayment(
        prExt,
        pd,
        action = None,
        sentAmount = cmd.split.myPart
      )
      LNParams.cm.localSend(cmd)
      alert.dismiss
    }

    def proceedSplit(
        prExt: PaymentRequestExt,
        origAmount: MilliSatoshi,
        alert: AlertDialog
    ): Unit = {
      val cmd = LNParams.cm
        .makeSendCmd(
          prExt,
          LNParams.cm.all.values.toList,
          LNParams.cm.feeReserve(manager.resultMsat),
          manager.resultMsat
        )
        .modify(_.split.totalSum)
        .setTo(origAmount)
      val pd = PaymentDescription(
        Some(cmd.split),
        label = manager.resultExtraInput,
        semanticOrder = None,
        invoiceText = prExt.descriptionOpt getOrElse new String
      )
      goToWithValue(
        value = SplitParams(prExt, action = None, pd, cmd, typicalChainTxFee),
        target = ClassNames.qrSplitActivityClass
      )
      alert.dismiss
    }
  }

  abstract class OffChainReceiver(
      into: Iterable[Channel],
      initMaxReceivable: MilliSatoshi,
      initMinReceivable: MilliSatoshi
  ) {
    val body: ViewGroup = getLayoutInflater
      .inflate(R.layout.frag_input_off_chain, null)
      .asInstanceOf[ViewGroup]
    val CommitsAndMax(cs, maxReceivable) =
      LNParams.cm.maxReceivable(LNParams.cm sortedReceivable into).get
    val manager: RateManager = getManager

    // It's important to cut down any msat leftover here, otherwise payment may become undeliverable
    val finalMaxReceivable: MilliSatoshi =
      initMaxReceivable.min(maxReceivable).truncateToSatoshi.toMilliSatoshi
    val finalMinReceivable: MilliSatoshi =
      initMinReceivable.min(finalMaxReceivable).max(LNParams.minPayment)
    val canReceiveHuman: String =
      WalletApp.denom.parsedWithSign(finalMaxReceivable)
    val canReceiveFiatHuman: String =
      WalletApp.currentMsatInFiatHuman(finalMaxReceivable)

    def receive(alert: AlertDialog): Unit = {
      val preimage: ByteVector32 = randomBytes32
      val description: PaymentDescription = getDescription
      val prExt = LNParams.cm.makePrExt(
        toReceive = manager.resultMsat,
        description,
        allowedChans = cs,
        Crypto.sha256(preimage),
        randomBytes32
      )
      LNParams.cm.payBag.replaceIncomingPayment(
        prExt,
        preimage,
        description,
        BaseActivity.totalBalance,
        LNParams.fiatRates.info.rates.filter { case (k, _) =>
          k == WalletApp.fiatCode
        }
      )
      WalletApp.app.showStickyNotification(
        R.string.incoming_notify_title,
        R.string.incoming_notify_body,
        manager.resultMsat
      )
      // This must be called AFTER PaymentInfo is present in db
      processInvoice(prExt)
      alert.dismiss
    }

    val alert: AlertDialog = {
      def setMax(alert1: AlertDialog): Unit =
        manager.updateText(finalMaxReceivable)
      val builder = titleBodyAsViewBuilder(
        getTitleText.asColoredView(R.color.ourPurple),
        manager.content
      )
      mkCheckFormNeutral(
        receive,
        none,
        setMax,
        builder,
        R.string.dialog_ok,
        R.string.dialog_cancel,
        R.string.dialog_max
      )
    }

    manager.inputAmount.addTextChangedListener(onTextChange { _ =>
      val withinBounds =
        finalMinReceivable <= manager.resultMsat && finalMaxReceivable >= manager.resultMsat
      updatePopupButton(
        button = getPositiveButton(alert),
        isEnabled = withinBounds
      )
    })

    manager.hintFiatDenom.setText(
      getString(R.string.dialog_up_to).format(canReceiveFiatHuman).html
    )
    manager.hintDenom.setText(
      getString(R.string.dialog_up_to).format(canReceiveHuman).html
    )
    updatePopupButton(getPositiveButton(alert), isEnabled = false)

    def getTitleText: String
    def getManager: RateManager
    def getDescription: PaymentDescription
    def processInvoice(prExt: PaymentRequestExt): Unit
  }
}

trait BaseCheckActivity extends BaseActivity {
  def PROCEED(state: Bundle): Unit

  override def onCreate(savedActivityState: Bundle): Unit = {
    super.onCreate(savedActivityState)
    if (!LNParams.isOperational)
      exitTo(ClassNames.mainActivityClass)
  }

  override def onResume(): Unit = runAnd(super.onResume) {
    if (AppLock.isUnlockRequired(this) && WalletApp.useAuth) {
      val intent: Intent = new Intent(this, ClassNames.unlockActivityClass)
      startActivityForResult(intent, AppLock.REQUEST_CODE_UNLOCK)
    }
  }

  override def START(state: Bundle): Unit = {
    if (LNParams.isOperational) PROCEED(state)
    else {
      // The way Android works is we can get some objects nullified when restoring from background
      // when that happens we make sure to free all remaining resources and start from scratch
      WalletApp.freePossiblyUsedRuntimeResouces()
      exitTo(ClassNames.mainActivityClass)
    }
  }
}

trait HasTypicalChainFee {
  lazy val typicalChainTxFee: MilliSatoshi = {
    val target =
      LNParams.feeRates.info.onChainFeeConf.feeTargets.mutualCloseBlockTarget
    val feerate =
      LNParams.feeRates.info.onChainFeeConf.feeEstimator.getFeeratePerKw(target)
    // Should not be used by long-lived instances since this info is getting outdated
    Transactions.weight2fee(feerate, weight = 750).toMilliSatoshi
  }

  def replaceOutgoingPayment(
      ext: PaymentRequestExt,
      description: PaymentDescription,
      action: Option[PaymentAction],
      sentAmount: MilliSatoshi,
      seenAt: Long = System.currentTimeMillis
  ): Unit =
    LNParams.cm.payBag.replaceOutgoingPayment(
      ext,
      description,
      action,
      sentAmount,
      BaseActivity.totalBalance,
      LNParams.fiatRates.info.rates.filter { case (k, _) =>
        k == WalletApp.fiatCode
      },
      typicalChainTxFee,
      seenAt
    )
}

trait ChanErrorHandlerActivity extends BaseCheckActivity {
  // Activities extending from this trait process unknown channel errors by default, also can be configured to handle other types of channel-related exceptions
  val channelErrors: Cache[ByteVector32, JInt] = CacheBuilder.newBuilder
    .expireAfterAccess(30, TimeUnit.SECONDS)
    .maximumSize(500)
    .build[ByteVector32, JInt]
  val MAX_ERROR_COUNT_WITHIN_WINDOW = 4

  def chanUnknown(
      worker: CommsTower.Worker,
      reestablish: ChannelReestablish
  ): Unit = UITask {
    val errorCount = Option(channelErrors getIfPresent reestablish.channelId)
      .getOrElse(0: JInt)
    if (errorCount >= MAX_ERROR_COUNT_WITHIN_WINDOW) return

    def break(alert: AlertDialog): Unit = runAnd(alert.dismiss)(
      worker.requestRemoteForceClose(reestablish.channelId)
    )
    val msg = getString(R.string.error_channel_unknown)
      .format(
        reestablish.channelId.toHex,
        worker.info.nodeSpecificPubKey.toString,
        worker.info.nodeId.toString
      )
      .html
    val builder = new AlertDialog.Builder(this, R.style.DialogTheme)
      .setCustomTitle(getString(R.string.error_channel).asDefView)
      .setCancelable(true)
      .setMessage(msg)
    mkCheckFormNeutral(
      _.dismiss,
      share(msg),
      break,
      builder,
      R.string.dialog_ok,
      R.string.dialog_share,
      R.string.dialog_break
    )
    channelErrors.put(reestablish.channelId, errorCount + 1)
  }.run

  def chanError(chanId: ByteVector32, msg: String, info: RemoteNodeInfo): Unit =
    UITask {
      val errorCount =
        Option(channelErrors getIfPresent chanId).getOrElse(default = 0: JInt)
      val builder = new AlertDialog.Builder(this, R.style.DialogTheme)
        .setCustomTitle(getString(R.string.error_channel).asDefView)
        .setMessage(msg.html)
      if (errorCount < MAX_ERROR_COUNT_WITHIN_WINDOW)
        mkCheckForm(
          _.dismiss,
          share(msg),
          builder,
          R.string.dialog_ok,
          R.string.dialog_share
        )
      channelErrors.put(chanId, errorCount + 1)
    }.run
}

trait QRActivity extends BaseCheckActivity {
  def shareData(bitmap: Bitmap, bech32: String): Unit = {
    val paymentRequestFilePath = new File(getCacheDir, "images")
    if (!paymentRequestFilePath.isFile) paymentRequestFilePath.mkdirs
    val out = new FileOutputStream(s"$paymentRequestFilePath/qr.png")
    bitmap.compress(Bitmap.CompressFormat.PNG, 85, out)
    out.close

    val savedFile = new File(paymentRequestFilePath, "qr.png")
    val fileURI =
      FileProvider.getUriForFile(this, "wtf.nbd.obw", savedFile)
    val share =
      (new Intent)
        .setAction(Intent.ACTION_SEND)
        .setType("text/plain")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    share
      .putExtra(Intent.EXTRA_TEXT, bech32)
      .putExtra(Intent.EXTRA_STREAM, fileURI)
      .setDataAndType(fileURI, getContentResolver getType fileURI)
    startActivity(Intent.createChooser(share, "Choose an app"))
  }

  class QRViewHolder(itemView: View) extends RecyclerView.ViewHolder(itemView) {
    val qrCode: ImageView =
      itemView.findViewById(R.id.qrCode).asInstanceOf[ImageView]
    val qrLabel: TextView =
      itemView.findViewById(R.id.qrLabel).asInstanceOf[TextView]
    val qrShare: AppCompatButton =
      itemView.findViewById(R.id.qrShare).asInstanceOf[AppCompatButton]
    val qrEdit: AppCompatButton =
      itemView.findViewById(R.id.qrEdit).asInstanceOf[AppCompatButton]
    val qrCopy: AppCompatButton =
      itemView.findViewById(R.id.qrCopy).asInstanceOf[AppCompatButton]
  }
}

object QRActivity {
  val writer = new QRCodeWriter
  val hints = new java.util.Hashtable[EncodeHintType, Any]
  hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
  hints.put(EncodeHintType.MARGIN, 1)

  def get(data: String, size: Int): Bitmap = {
    val bitMatrix =
      writer.encode(data, BarcodeFormat.QR_CODE, size, size, hints)
    val (width, height) = (bitMatrix.getWidth, bitMatrix.getHeight)
    val pixels = new Array[Int](width * height)

    for {
      xPos <- 0 until width
      yPos <- 0 until height
      isBlack = bitMatrix.get(xPos, yPos)
      color = if (isBlack) Color.BLACK else Color.WHITE
    } pixels(yPos * width + xPos) = color

    val qrBitmap = Bitmap.createBitmap(width, height, ARGB_8888)
    qrBitmap.setPixels(pixels, 0, size, 0, 0, width, height)
    qrBitmap
  }
}

abstract class ChainWalletCards(host: BaseActivity) { self =>
  private var cardViews: List[ChainCard] = Nil

  def init(wallets: List[ElectrumEclairWallet] = Nil): Unit = {
    cardViews = List.fill(wallets.size)(new ChainCard)
    cardViews.map(_.view).foreach(holder.addView)
  }

  def update(wallets: List[ElectrumEclairWallet] = Nil): Unit =
    cardViews.zip(wallets).foreach { case (card, wallet) =>
      card updateView wallet
    }

  def unPad(wallets: List[ElectrumEclairWallet] = Nil): Unit =
    cardViews.foreach(_.unPad())

  class ChainCard {
    val view: SwipeRevealLayout = host.getLayoutInflater
      .inflate(R.layout.frag_chain_card, null)
      .asInstanceOf[SwipeRevealLayout]
    val chainPaddingWrap: LinearLayout =
      view.findViewById(R.id.chainPaddingWrap).asInstanceOf[LinearLayout]
    val chainWrap: CardView =
      view.findViewById(R.id.chainWrap).asInstanceOf[CardView]

    val chainContainer: View =
      view.findViewById(R.id.chainContainer).asInstanceOf[View]
    val coinControlOn: ImageView =
      view.findViewById(R.id.coinControlOn).asInstanceOf[ImageView]
    val setItemLabel: NoboButton =
      view.findViewById(R.id.setItemLabel).asInstanceOf[NoboButton]
    val coinControl: NoboButton =
      view.findViewById(R.id.coinControl).asInstanceOf[NoboButton]
    val removeItem: NoboButton =
      view.findViewById(R.id.removeItem).asInstanceOf[NoboButton]

    val chainLabel: TextView =
      view.findViewById(R.id.chainLabel).asInstanceOf[TextView]
    val chainWalletNotice: TextView =
      view.findViewById(R.id.chainWalletNotice).asInstanceOf[TextView]

    val chainBalanceWrap: LinearLayout =
      view.findViewById(R.id.chainBalanceWrap).asInstanceOf[LinearLayout]
    val chainBalanceFiat: TextView =
      view.findViewById(R.id.chainBalanceFiat).asInstanceOf[TextView]
    val chainBalance: TextView =
      view.findViewById(R.id.chainBalance).asInstanceOf[TextView]

    val receiveBitcoinTip: ImageView =
      view.findViewById(R.id.receiveBitcoinTip).asInstanceOf[ImageView]
    val showMenuTip: ImageView =
      view.findViewById(R.id.showMenuTip).asInstanceOf[ImageView]

    def unPad(): Unit = {
      val padding: Int = chainPaddingWrap.getPaddingTop
      chainPaddingWrap.setPadding(padding, padding, padding, 0)
      view.setLockDrag(true)
    }

    def updateView(wallet: ElectrumEclairWallet): Unit = {
      chainBalance.setText(
        WalletApp.denom
          .parsedWithSign(wallet.getData.balance.toMilliSatoshi)
          .html
      )
      chainBalanceFiat.setText(
        WalletApp.currentMsatInFiatHuman(wallet.getData.balance.toMilliSatoshi)
      )

      val chainBalanceVisible = wallet.getData.balance > 0L.sat
      val plusTipVisible =
        (wallet.isBuiltIn || !wallet.isSigning) && !chainBalanceVisible
      val menuTipVisible =
        !(wallet.isBuiltIn || !wallet.isSigning) && !chainBalanceVisible

      host.setVisMany(
        chainBalanceVisible -> chainBalanceWrap,
        plusTipVisible -> receiveBitcoinTip,
        menuTipVisible -> showMenuTip
      )
      host.setVisMany(
        wallet.info.core.isRemovable -> setItemLabel,
        wallet.info.core.isRemovable -> removeItem,
        wallet.info.isCoinControlOn -> coinControlOn
      )

      host.chainWalletNotice(wallet) foreach { textRes =>
        host.setVis(isVisible = true, chainWalletNotice)
        chainWalletNotice.setText(textRes)
      }

      chainContainer.setBackgroundResource(host chainWalletBackground wallet)
      setItemLabel setOnClickListener host.onButtonTap(self onLabelTap wallet)
      coinControl setOnClickListener host.onButtonTap(
        self onCoinControlTap wallet
      )
      removeItem setOnClickListener host.onButtonTap(self onRemoveTap wallet)
      chainWrap setOnClickListener host.onButtonTap(self onWalletTap wallet)
      chainLabel setText wallet.info.label
    }
  }

  def onLabelTap(wallet: ElectrumEclairWallet): Unit
  def onRemoveTap(wallet: ElectrumEclairWallet): Unit
  def onCoinControlTap(wallet: ElectrumEclairWallet): Unit
  def onWalletTap(wallet: ElectrumEclairWallet): Unit
  def holder: LinearLayout
}
