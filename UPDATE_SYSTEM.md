# 🔄 Система обновлений DiceAutoBet

Это приложение поддерживает автоматическую проверку обновлений через GitHub Releases.

## 📋 Как это работает:

1. **При запуске приложения** автоматически проверяется наличие новых версий (раз в 24 часа)
2. **Если доступно обновление**, показывается диалог с описанием изменений
3. **Пользователь выбирает**: обновить сейчас, пропустить версию или отменить
4. **APK скачивается** через Android DownloadManager
5. **Автоматически открывается установщик** после завершения загрузки

## 🚀 Настройка для вашего проекта:

### Шаг 1: Измените URL в UpdateManager.kt

Откройте `app/src/main/java/com/example/diceautobet/utils/UpdateManager.kt` и замените:

```kotlin
private const val UPDATE_JSON_URL = "https://raw.githubusercontent.com/yourusername/DiceAutoBet/main/update.json"
```

На ваш реальный URL (например):
```kotlin
private const val UPDATE_JSON_URL = "https://raw.githubusercontent.com/YourName/DiceAutoBet/main/update.json"
```

### Шаг 2: Загрузите update.json в ваш репозиторий GitHub

1. Откройте файл `update.json` в корне проекта
2. Обновите значения:
   ```json
   {
     "latestVersion": "1.0.1",
     "versionCode": 2,
     "downloadUrl": "https://github.com/YourName/DiceAutoBet/releases/download/v1.0.1/app-release.apk",
     "changelog": "✨ Что нового в этой версии",
     "mandatory": false,
     "minSupportedVersion": "1.0.0"
   }
   ```
3. Закоммитьте файл в репозиторий GitHub

### Шаг 3: Создайте релиз на GitHub

1. Перейдите на вкладку **Releases** в вашем репозитории
2. Нажмите **Create a new release**
3. Введите тег версии (например: `v1.0.1`)
4. Загрузите APK файл (`app-release.apk`)
5. Опубликуйте релиз

### Шаг 4: Обновите versionCode в build.gradle.kts

Перед каждым релизом увеличивайте `versionCode` в `app/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        versionCode = 2  // Увеличивайте для каждой новой версии
        versionName = "1.0.1"
    }
}
```

## 📝 Формат update.json:

```json
{
  "latestVersion": "1.0.1",          // Имя версии (отображается пользователю)
  "versionCode": 2,                   // Код версии (должен совпадать с build.gradle)
  "downloadUrl": "URL_к_APK",         // Прямая ссылка на APK файл
  "changelog": "Описание изменений",  // Что нового (можно многострочный текст)
  "mandatory": false,                 // true = обязательное обновление (нельзя пропустить)
  "minSupportedVersion": "1.0.0"      // Минимальная поддерживаемая версия
}
```

## 🎯 Типы обновлений:

### Обычное обновление (mandatory: false):
- Пользователь может пропустить
- Показывается 3 кнопки: "Обновить", "Пропустить", "Отмена"

### Обязательное обновление (mandatory: true):
- Пользователь не может пропустить
- Показывается только кнопка "Обновить"
- Используйте для критических обновлений безопасности

## 🔒 Безопасность:

- APK должен быть подписан тем же ключом для успешной установки
- Android проверяет подпись перед установкой
- Используйте HTTPS для update.json и APK
- FileProvider обеспечивает безопасный доступ к файлам

## 🧪 Тестирование:

1. Установите текущую версию (например versionCode=1)
2. Обновите `update.json` с versionCode=2
3. Перезапустите приложение
4. Должен появиться диалог обновления

## 💡 Советы:

- Проверка обновлений происходит максимум раз в 24 часа
- Пропущенные версии не показываются повторно
- Логи обновлений сохраняются в FileLogger
- Можно добавить кнопку "Проверить обновления" в меню (метод `checkForUpdatesManually()` уже готов)

## 🐛 Отладка:

Логи обновлений можно найти в:
- Logcat с тегом `UpdateManager`
- Файл логов приложения (кнопка "Отправить логи")

Проверьте:
- ✅ URL в UpdateManager.kt корректный
- ✅ update.json доступен по URL
- ✅ versionCode в update.json больше текущего
- ✅ downloadUrl ведёт на рабочий APK файл
- ✅ Разрешение REQUEST_INSTALL_PACKAGES в манифесте
