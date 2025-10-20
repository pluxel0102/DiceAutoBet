# 🚀 Инструкция по загрузке в GitHub (для вас лично)

## ⚠️ ВАЖНО: Безопасность токена

Ваш GitHub токен находится в файле `UpdateManager.kt`. 
**НЕ УДАЛЯЙТЕ ЕГО!** Он нужен приложению для проверки обновлений.

Токен: `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`
Репозиторий: https://github.com/pluxel0102/DiceAutoBet

---

## 📋 ШАГ 1: Установите Git (если ещё не установлен)

Скачайте Git с https://git-scm.com/download/win и установите.

---

## 📋 ШАГ 2: Загрузите код в GitHub

Откройте PowerShell в папке проекта и выполните:

```powershell
# Перейдите в папку проекта
cd C:\Users\User\AndroidStudioProjects\DiceAutoBet

# Инициализируйте Git репозиторий
git init

# Добавьте все файлы
git add .

# Создайте первый коммит
git commit -m "Initial commit: DiceAutoBet v1.0.0 с системой обновлений"

# Подключите удалённый репозиторий
git branch -M main
git remote add origin https://github.com/pluxel0102/DiceAutoBet.git

# Загрузите код в GitHub
git push -u origin main
```

При запросе логина и пароля введите:
- **Username**: `pluxel0102`
- **Password**: `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` (ваш токен)

---

## 📋 ШАГ 3: Соберите Release APK

### В Android Studio:

1. **Build** → **Generate Signed Bundle / APK**
2. Выберите **APK**
3. Нажмите **Next**
4. **Create new...** (создать новый keystore):
   - **Key store path**: Сохраните в безопасном месте (например: `C:\Users\User\Documents\DiceAutoBet\keystore.jks`)
   - **Password**: Придумайте надёжный пароль (ЗАПИШИТЕ ЕГО!)
   - **Alias**: `diceautobet`
   - **Password**: Пароль для ключа (может быть таким же)
   - **Validity (years)**: `25`
   - **Certificate**:
     - First and Last Name: `DiceAutoBet`
     - Organizational Unit: `Development`
     - Organization: `Personal`
     - City: `Moscow`
     - State: `Russia`
     - Country Code: `RU`
5. Нажмите **OK** → **Next**
6. Выберите **release** build variant
7. Нажмите **Create**

APK будет создан в: `C:\Users\User\AndroidStudioProjects\DiceAutoBet\app\release\app-release.apk`

**⚠️ КРИТИЧНО:** Сохраните файл `keystore.jks` и пароли! Без них вы не сможете обновлять приложение!

---

## 📋 ШАГ 4: Создайте первый релиз на GitHub

1. Откройте: https://github.com/pluxel0102/DiceAutoBet
2. Перейдите на вкладку **Releases**
3. Нажмите **Create a new release**
4. Заполните:
   - **Choose a tag**: введите `v1.0.0` и нажмите **Create new tag**
   - **Release title**: `DiceAutoBet v1.0.0 - Первый релиз`
   - **Description**:
     ```markdown
     ## 🎉 Первый релиз DiceAutoBet!
     
     ### ✨ Основные функции:
     - 🤖 Автоматическое распознавание кубиков через AI (OpenRouter)
     - 🎯 Автоматические ставки с удвоением
     - 🔵🔴 Смена цвета после проигрышей
     - 📱 Toast-уведомления с результатами
     - 📝 Комплексное логирование
     - 🔄 Система автоматических обновлений
     
     ### 📥 Установка:
     1. Скачайте `app-release.apk` ниже
     2. Откройте файл на телефоне
     3. Разрешите установку из неизвестных источников
     4. Установите APK
     5. Следуйте инструкциям в приложении
     
     ### 🔧 Требования:
     - Android 7.0 (API 24) или выше
     - OpenRouter API ключ
     ```
5. **Attach binaries**: Перетащите файл `app-release.apk` в область загрузки
6. Нажмите **Publish release**

---

## 📋 ШАГ 5: Проверьте URL загрузки APK

После публикации релиза, скопируйте URL загрузки APK. Он должен выглядеть так:
```
https://github.com/pluxel0102/DiceAutoBet/releases/download/v1.0.0/app-release.apk
```

Этот URL уже прописан в `update.json` - ничего менять не нужно! ✅

---

## 📋 ШАГ 6: Проверьте update.json

Файл `update.json` уже обновлён и готов к загрузке в репозиторий. Он содержит:

```json
{
  "latestVersion": "1.0.0",
  "versionCode": 1,
  "downloadUrl": "https://github.com/pluxel0102/DiceAutoBet/releases/download/v1.0.0/app-release.apk",
  "changelog": "🎉 Первый релиз!...",
  "mandatory": false,
  "minSupportedVersion": "1.0.0"
}
```

Если вы уже загрузили код (Шаг 2), теперь нужно обновить `update.json` в репозитории:

```powershell
cd C:\Users\User\AndroidStudioProjects\DiceAutoBet
git add update.json
git commit -m "Добавлена информация об обновлении v1.0.0"
git push
```

---

## 📋 ШАГ 7: Проверьте работу обновлений

1. Установите собранный APK на телефон
2. Запустите приложение
3. Приложение автоматически проверит обновления при запуске
4. Если всё настроено правильно, вы увидите в логах:
   ```
   ✅ Версия актуальна: 1.0.0
   ```

---

## 🎯 Для следующего обновления (v1.0.1):

1. **Обновите версию** в `app/build.gradle.kts`:
   ```kotlin
   versionCode = 2
   versionName = "1.0.1"
   ```

2. **Соберите новый APK** (тем же keystore!)

3. **Создайте новый релиз** на GitHub:
   - Tag: `v1.0.1`
   - Загрузите новый APK

4. **Обновите update.json**:
   ```json
   {
     "latestVersion": "1.0.1",
     "versionCode": 2,
     "downloadUrl": "https://github.com/pluxel0102/DiceAutoBet/releases/download/v1.0.1/app-release.apk",
     "changelog": "✨ Что нового в 1.0.1",
     "mandatory": false,
     "minSupportedVersion": "1.0.0"
   }
   ```

5. **Закоммитьте изменения**:
   ```powershell
   git add update.json
   git commit -m "Update to v1.0.1"
   git push
   ```

6. **Запустите старую версию** (v1.0.0) - должен появиться диалог обновления!

---

## 🔐 Безопасность:

✅ **Токен в коде**: Это нормально для приватного репозитория. Токен используется только для чтения `update.json`.

✅ **Keystore**: НЕ загружайте в Git! Уже добавлен в `.gitignore`.

✅ **Приватный репозиторий**: Только вы имеете доступ к коду.

---

## 📞 Если что-то не работает:

### Проблема: Не удаётся загрузить код в GitHub
**Решение**: Проверьте, что используете токен вместо пароля

### Проблема: Приложение не видит обновления
**Решение**: Проверьте, что:
- `update.json` загружен в репозиторий
- URL в `update.json` правильный
- Релиз на GitHub опубликован
- APK загружен в релиз

### Проблема: Ошибка установки обновления
**Решение**: APK должен быть подписан тем же keystore

---

## ✅ Контрольный список:

- [ ] Git установлен
- [ ] Код загружен в GitHub
- [ ] Keystore создан и сохранён в безопасном месте
- [ ] Release APK собран
- [ ] Релиз v1.0.0 создан на GitHub
- [ ] APK загружен в релиз
- [ ] update.json обновлён и загружен
- [ ] Приложение установлено и протестировано

---

## 🎉 Готово!

Ваше приложение теперь может автоматически обновляться! 🚀

Репозиторий: https://github.com/pluxel0102/DiceAutoBet
Токен: `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`
