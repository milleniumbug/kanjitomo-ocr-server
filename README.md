# kanjitomo-ocr-server

A REST API server that exposes the OCR recognition capabilities of [KanjiTomo application](https://github.com/sakarika/kanjitomo-ocr), for easy integration with other applications.

## License

[The kanjitomo-ocr library uses a license that allows its use for non-commercial purposes, with attribution.](https://github.com/sakarika/kanjitomo-ocr/blob/master/LICENSE.txt)

This project uses the kanjitomo-ocr library, therefore it is bound by its license.

## Calling the API

With the server up and running, run

    curl --data-binary @/path/to/my/image/file http://localhost:8080/ocr

Sample response:

    {"status":"success","result":"今夜はうぢに\n泊まりに芯いでよ\n","isVertical":true}
