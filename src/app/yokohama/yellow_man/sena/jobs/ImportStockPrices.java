package yokohama.yellow_man.sena.jobs;

import java.util.Date;
import java.util.List;
import java.util.Random;

import play.Play;
import yokohama.yellow_man.common_tools.CheckUtils;
import yokohama.yellow_man.sena.components.db.StockPricesComponent;
import yokohama.yellow_man.sena.components.db.StocksComponent;
import yokohama.yellow_man.sena.components.scraping.ScrapingComponent;
import yokohama.yellow_man.sena.components.scraping.ScrapingException;
import yokohama.yellow_man.sena.components.scraping.entity.StockPricesEntity;
import yokohama.yellow_man.sena.core.components.AppLogger;
import yokohama.yellow_man.sena.core.models.StockPrices;
import yokohama.yellow_man.sena.core.models.Stocks;
import yokohama.yellow_man.sena.jobs.JobExecutor.JobArgument;

/**
 * 株価情報インポートバッチクラス。
 * <p>銘柄情報（stocks）を元に株価情報を取得し、
 * 株価（stock_prices）テーブルにインポートする。
 * 相手方にアクセス負荷とならぬよう、1件取得ごとにランダムでインターバル(2秒～5秒)を設けている。
 *
 * @author yellow-man
 * @since 1.1.0-1.2
 * @version 1.1.1-1.2
 */
public class ImportStockPrices extends AppLoggerMailJob {

	/**
	 * メールタイトル
	 * {@code application.conf}ファイル{@code stock_prices.mail_title}キーにて値の変更可。
	 */
	private static final String IMPORT_STOCK_PRICES_MAIL_TITLE    = Play.application().configuration().getString("stock_prices.mail_title", "[sena]株価情報インポートバッチ実行結果");

	/**
	 * 株価情報インポートバッチクラスコンストラクタ。
	 * @since 1.1.0-1.2
	 */
	public ImportStockPrices() {
		// メールタイトル
		this.emailTitle  = IMPORT_STOCK_PRICES_MAIL_TITLE;
		// ログファイル名
		this.logFileName = getClass().getName() + "." + this.logDateFormat.format(new Date()) + ".log";
		// ログファイルpath
		this.logFilePath = LOG_FILE_PATH + getClass().getName() + "/" + this.logFileName;
	}

	/**
	 * バッチメイン処理。
	 * <p>下記、起動引数に対応。
	 * <ul>
	 * <li>{@code -sd} 取得対象開始日（フォーマット：YYYY-MM-DD）</li>
	 * <li>{@code -ed} 取得対象終了日（フォーマット：YYYY-MM-DD）</li>
	 * <li>{@code -min} 取得対象最小銘柄コード（指定された銘柄コード以降の銘柄情報を取得する。）</li>
	 * <li>{@code -max} 取得対象最大銘柄コード（指定された銘柄コードまでの銘柄情報を取得する。）</li>
	 * <li>{@code -minIntervalSec} インターバル最小値（インターバルとして指定する最小値。デフォルト：2秒）</li>
	 * <li>{@code -maxIntervalSec} インターバル最大値（インターバルとして指定する最大値。デフォルト：5秒）</li>
	 * </ul>
	 * @param args 起動引数
	 * @see yokohama.yellow_man.sena.jobs.AppJob#run(yokohama.yellow_man.sena.jobs.JobExecutor.JobArgument)
	 * @since 1.1.0-1.2
	 */
	@Override
	protected void run(JobArgument args) {
		AppLogger.info("株価情報インポートバッチ　開始");

		// 成功件数
		int success = 0;
		// エラー件数
		int error = 0;
		// スキップ件数
		int skip = 0;

		// 銘柄情報取得
		List<Stocks> stocksList = StocksComponent.getStocksList(args.minStockCode, args.maxStockCode);
		if (CheckUtils.isEmpty(stocksList)) {
			AppLogger.warn("銘柄情報が取得できませんでした。");

		} else {
			for (Stocks stocks : stocksList) {
				Integer stockCode = stocks.stockCode;
				String stockName = stocks.stockName;

				try {
					// 外部サイトから株価情報を取得
					List<StockPricesEntity> stockPricesEntityList = ScrapingComponent.getStockPricesList(stockCode, args.startDate, args.endDate, null);

					if (CheckUtils.isEmpty(stockPricesEntityList)) {
						AppLogger.warn(new StringBuffer("株価リストが取得できませんでした。：")
								.append(stockCode).append(":").append(stockName)
								.toString());

						skip++;
					} else {

						// モデルに詰め替えDBに保存
						int ret = _saveStockPrices(stockCode, args.startDate, args.endDate, stockPricesEntityList);
						switch (ret) {
							case 0:
								success++;
								break;

							case 1:
								error++;
								break;

							case 2:
								skip++;
								break;
						}
					}

				} catch (ScrapingException e) {

					// 取得できない場合は、処理を飛ばす。
					AppLogger.error(new StringBuffer("外部サイトから株価情報が取得できませんでした。：")
							.append(stockCode).append(":").append(stockName)
							.toString(), e);

					error++;
					continue;
				}

				// インターバル(2秒～5秒)
				try {
					Random rnd = new Random();
					int ran = rnd.nextInt(args.maxIntervalSec - args.minIntervalSec) + args.minIntervalSec;
					Thread.sleep(1000 * ran);
				} catch (InterruptedException e) {
					AppLogger.error("インターバル取得時にエラーが発生しました。", e);
				}
			}
		}
		AppLogger.info("株価情報インポートバッチ　終了：処理件数=" + String.valueOf(success + error + skip) + ", 成功件数=" + success + ", 失敗件数=" + error + ", スキップ件数=" + skip);
	}

	/**
	 * エンティティから、モデルにデータを詰め替えDBに保存する。
	 * 時間はかかるが、バルクインサートは使用せず1件ずつ処理を行う。
	 *
	 * @param stockCode 銘柄コード
	 * @param startDate 取得開始日
	 * @param endDate 取得終了日
	 * @param stockPricesEntityList 株価エンティティのリスト
	 * @return 1件インポートが成功したら（0..成功）、ただし例外が発生していたら（1..失敗）、1件も処理しなかったら（2..スキップ）
	 * @since 1.1.0-1.2
	 */
	private int _saveStockPrices(Integer stockCode, Date startDate, Date endDate, List<StockPricesEntity> stockPricesEntityList) {
		int ret = 2;

		// 登録済みの株価情報を取得する。
		List<Long> dateTimeList = StockPricesComponent.getDateTimeListByStockCode(stockCode, startDate, endDate);

		for (StockPricesEntity stockPricesEntity : stockPricesEntityList) {
			Date date = stockPricesEntity.date;

			if (date == null
					|| (dateTimeList != null && dateTimeList.contains(Long.valueOf(date.getTime())))) {
				AppLogger.info("日付が変換できないか、既に登録済みの為処理をスキップしました。：stockPricesEntity=" + stockPricesEntity);
				continue;
			}

			StockPrices stockPrices = new StockPrices();
			try {
				stockPrices.date                 = stockPricesEntity.date;
				stockPrices.stockCode            = stockPricesEntity.stockCode;
				stockPrices.openingPrice         = yokohama.yellow_man.common_tools.NumberUtils.toBigDecimal(stockPricesEntity.openingPrice, null);
				stockPrices.highPrice            = yokohama.yellow_man.common_tools.NumberUtils.toBigDecimal(stockPricesEntity.highPrice, null);
				stockPrices.lowPrice             = yokohama.yellow_man.common_tools.NumberUtils.toBigDecimal(stockPricesEntity.lowPrice, null);
				stockPrices.closingPrice         = yokohama.yellow_man.common_tools.NumberUtils.toBigDecimal(stockPricesEntity.closingPrice, null);
				stockPrices.turnover             = yokohama.yellow_man.common_tools.NumberUtils.toBigDecimal(stockPricesEntity.turnover, null);
				stockPrices.adjustedClosingPrice = yokohama.yellow_man.common_tools.NumberUtils.toBigDecimal(stockPricesEntity.adjustedClosingPrice, null);
				stockPrices.created              = new Date();
				stockPrices.modified             = new Date();
				stockPrices.deleteFlg            = false;
				stockPrices.save();

				// 例外が発生していたら、失敗として扱う
				if (ret != 1) {
					ret = 0;
				}
			} catch (Exception e) {
				AppLogger.error("株価DB保存時にエラーが発生しました。：stockPricesEntity=" + stockPricesEntity, e);
				ret = 1;
			}
		}
		return ret;
	}
}
