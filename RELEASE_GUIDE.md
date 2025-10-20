# 🚀 Инструкция по созданию первого релиза

## Шаг 1: Обновите версию в build.gradle.kts

Откройте `app/build.gradle.kts` и убедитесь, что версия установлена:

```kotlin
android {
    defaultConfig {
        applicationId = "com.example.diceautobet"
        minSdk = 24
        targetSdk = 34
        versionCode = 1  // ← Это должно быть 1 для первого релиза
        versionName = "1.0.0"  // ← Отображается пользователю
    }
}
```

## Шаг 2: Соберите Release APK

В Android Studio:
1. Выберите **Build** → **Generate Signed Bundle / APK**
2. Выберите **APK**
3. Создайте новый keystore (или используйте существующий)
4. Заполните данные keystore:
   - Key store path: выберите место для сохранения
   - Password: придумайте надёжный пароль
   - Key alias: например `diceautobet`
   - Key password: пароль для ключа
5. Нажмите **Next**
6. Выберите **release** build variant
7. Нажмите **Finish**

APK будет создан в: `app/release/app-release.apk`

**ВАЖНО:** Сохраните keystore файл и пароли! Без них вы не сможете обновлять приложение!

## Шаг 3: Создайте репозиторий на GitHub

1. Перейдите на https://github.com
2. Нажмите **New repository**
3. Назовите: `DiceAutoBet`
4. Выберите **Public** (для публичного доступа к update.json)
5. Нажмите **Create repository**

## Шаг 4: Загрузите код в GitHub

```bash
cd C:\Users\User\AndroidStudioProjects\DiceAutoBet
git init
git add .
git commit -m "Initial commit: DiceAutoBet v1.0.0"
git branch -M main
git remote add origin https://github.com/YOUR_USERNAME/DiceAutoBet.git
git push -u origin main
```

## Шаг 5: Создайте первый релиз на GitHub

1. Откройте ваш репозиторий на GitHub
2. Перейдите на вкладку **Releases**
3. Нажмите **Create a new release**
4. Заполните:
   - **Tag version**: `v1.0.0`
   - **Release title**: `DiceAutoBet v1.0.0 - Первый релиз`
   - **Description**: 
     ```markdown
     ## 🎉 Первый релиз DiceAutoBet!
     
     ### ✨ Основные функции:
     - Автоматическое распознавание кубиков через AI
     - Поддержка OpenRouter API (Claude, GPT-4o, Gemini)
     - Автоматические ставки с удвоением
     - Toast-уведомления с результатами
     - Комплексное логирование
     - Система автообновлений
     
     ### 📥 Установка:
     1. Скачайте `app-release.apk`
     2. Разрешите установку из неизвестных источников
     3. Установите APK
     4. Следуйте инструкциям в приложении
     ```
5. Загрузите **app-release.apk** (перетащите файл в секцию "Attach binaries")
6. Нажмите **Publish release**

## Шаг 6: Обновите update.json

После публикации релиза:

1. Скопируйте URL загрузки APK (например):
   ```
   https://github.com/YOUR_USERNAME/DiceAutoBet/releases/download/v1.0.0/app-release.apk
   ```

2. Откройте `update.json` и обновите:
   ```json
   {
     "latestVersion": "1.0.0",
     "versionCode": 1,
     "downloadUrl": "https://github.com/YOUR_USERNAME/DiceAutoBet/releases/download/v1.0.0/app-release.apk",
     "changelog": "🎉 Первый релиз!\n\n✨ Основные функции:\n• Распознавание кубиков через AI\n• Автоматические ставки\n• Поддержка OpenRouter API\n• Toast-уведомления\n• Система логирования",
     "mandatory": false,
     "minSupportedVersion": "1.0.0"
   }
   ```

3. Закоммитьте изменения:
   ```bash
   git add update.json
   git commit -m "Добавлена информация об обновлении v1.0.0"
   git push
   ```

## Шаг 7: Обновите URL в UpdateManager.kt

Откройте `app/src/main/java/com/example/diceautobet/utils/UpdateManager.kt`:

```kotlin
private const val UPDATE_JSON_URL = "https://raw.githubusercontent.com/YOUR_USERNAME/DiceAutoBet/main/update.json"
```

Замените `YOUR_USERNAME` на ваше имя пользователя GitHub.

## Шаг 8: Протестируйте систему обновлений

### Для второго релиза:

1. Увеличьте версию в `build.gradle.kts`:
   ```kotlin
   versionCode = 2
   versionName = "1.0.1"
   ```

2. Внесите изменения в код

3. Соберите новый Release APK (тем же keystore!)

4. Создайте новый релиз на GitHub (`v1.0.1`)

5. Обновите `update.json`:
   ```json
   {
     "latestVersion": "1.0.1",
     "versionCode": 2,
     "downloadUrl": "https://github.com/YOUR_USERNAME/DiceAutoBet/releases/download/v1.0.1/app-release.apk",
     "changelog": "🐛 Исправления:\n• Исправлена ошибка X\n• Улучшена производительность",
     "mandatory": false,
     "minSupportedVersion": "1.0.0"
   }
   ```

6. Запустите старую версию приложения (v1.0.0)

7. Должен появиться диалог обновления!

## 📝 Контрольный список для каждого релиза:

- [ ] Увеличен `versionCode` в `build.gradle.kts`
- [ ] Обновлён `versionName` в `build.gradle.kts`
- [ ] Собран Release APK с тем же keystore
- [ ] Создан релиз на GitHub с тегом версии
- [ ] APK загружен в релиз
- [ ] Обновлён `update.json` с правильными URL и versionCode
- [ ] Изменения закоммичены и запушены в GitHub
- [ ] Протестировано обновление со старой версии

## 🔐 Безопасность keystore:

**НИКОГДА НЕ ЗАГРУЖАЙТЕ KEYSTORE В GIT!**

Добавьте в `.gitignore`:
```
*.jks
*.keystore
keystore.properties
```

Сохраните keystore в безопасном месте (облако, флешка, etc.)

## 💡 Полезные команды Git:

```bash
# Создать тег
git tag v1.0.0
git push origin v1.0.0

# Удалить тег (если ошиблись)
git tag -d v1.0.0
git push origin :refs/tags/v1.0.0

# Посмотреть все теги
git tag -l
```

## 🎯 Готово!

Теперь ваше приложение будет автоматически проверять обновления при каждом запуске! 🚀
