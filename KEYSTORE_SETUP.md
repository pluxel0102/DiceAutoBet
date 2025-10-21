# Настройка Keystore для подписи APK

## Зачем нужен keystore?

Keystore - это файл с цифровым сертификатом для подписи вашего Android приложения. Без подписи APK не установится на устройство.

⚠️ **ВАЖНО:** Keystore должен храниться в **СЕКРЕТЕ**! Никогда не загружайте его в Git или публичные репозитории.

## Шаг 1: Создание keystore

### Способ 1: Через Android Studio (рекомендуется)

1. **Откройте меню Build:**
   - `Build` → `Generate Signed Bundle / APK...`

2. **Выберите APK:**
   - Отметьте `APK` → нажмите `Next`

3. **Создайте новый keystore:**
   - Нажмите `Create new...`

4. **Заполните поля:**
   - **Key store path:** `C:\Users\User\AndroidStudioProjects\DiceAutoBet\app\diceautobet-release.keystore`
   - **Password:** придумайте надёжный пароль (минимум 6 символов)
   - **Confirm:** повторите пароль
   - **Alias:** `diceautobet`
   - **Password:** пароль для ключа (можно тот же)
   - **Confirm:** повторите пароль
   - **Validity (years):** `25` (или больше)
   - **Certificate:**
     - **First and Last Name:** `DiceAutoBet`
     - **Organizational Unit:** `DiceAutoBet Dev`
     - **Organization:** `DiceAutoBet`
     - **City or Locality:** ваш город
     - **State or Province:** ваш регион
     - **Country Code (XX):** `RU`

5. **Нажмите OK** - keystore будет создан!

6. **НЕ продолжайте сборку через мастер** - нажмите `Cancel`
   - Keystore уже создан, дальше настроим через файлы

### Способ 2: Через командную строку

Откройте терминал (PowerShell) в корневой папке проекта и выполните:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
& "$env:JAVA_HOME\bin\keytool.exe" -genkey -v -keystore app/diceautobet-release.keystore -alias diceautobet -keyalg RSA -keysize 2048 -validity 10000
```

### Команда попросит ввести:

1. **Пароль keystore** - придумайте надёжный пароль (минимум 6 символов)
2. **Имя и фамилию** - можно указать название приложения: `DiceAutoBet`
3. **Организацию** - например: `DiceAutoBet Dev`
4. **Город** - ваш город
5. **Область/регион** - ваш регион
6. **Код страны** - например: `RU`
7. **Подтверждение** - введите `yes`
8. **Пароль для ключа** - можно оставить таким же, нажав Enter

### Пример:

```
Enter keystore password: ********
Re-enter new password: ********
What is your first and last name?
  [Unknown]:  DiceAutoBet
What is the name of your organizational unit?
  [Unknown]:  DiceAutoBet Dev
What is the name of your organization?
  [Unknown]:  DiceAutoBet
What is the name of your City or Locality?
  [Unknown]:  Moscow
What is the name of your State or Province?
  [Unknown]:  Moscow
What is the two-letter country code for this unit?
  [Unknown]:  RU
Is CN=DiceAutoBet, OU=DiceAutoBet Dev, O=DiceAutoBet, L=Moscow, ST=Moscow, C=RU correct?
  [no]:  yes

Generating 2,048 bit RSA key pair and self-signed certificate (SHA384withRSA) with a validity of 10,000 days
        for: CN=DiceAutoBet, OU=DiceAutoBet Dev, O=DiceAutoBet, L=Moscow, ST=Moscow, C=RU
[Storing app/diceautobet-release.keystore]
```

## Шаг 2: Создание keystore.properties

Создайте файл `keystore.properties` в **корневой папке проекта** со следующим содержимым:

```properties
storeFile=app/diceautobet-release.keystore
storePassword=ВАШ_ПАРОЛЬ_KEYSTORE
keyAlias=diceautobet
keyPassword=ВАШ_ПАРОЛЬ_КЛЮЧА
```

Замените:
- `ВАШ_ПАРОЛЬ_KEYSTORE` - на пароль, который вы придумали на шаге 1
- `ВАШ_ПАРОЛЬ_КЛЮЧА` - на пароль ключа (если оставили пустым, используйте тот же пароль)

### ⚠️ Важно:
- Файл `keystore.properties` уже добавлен в `.gitignore` и **не попадёт в Git**
- Keystore файл (`*.keystore`) также в `.gitignore`

## Шаг 3: Сборка подписанного APK

После создания keystore и keystore.properties, просто соберите Release APK:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleRelease
```

Готовый подписанный APK будет в:
```
app/build/outputs/apk/release/app-release.apk
```

## Шаг 4: Установка на устройство

```powershell
adb install -r app/build/outputs/apk/release/app-release.apk
```

## Безопасность

### ✅ Правильно:
- Хранить keystore локально (не загружать в Git)
- Хранить пароли в keystore.properties (не загружать в Git)
- Сделать резервную копию keystore в безопасное место
- Использовать надёжные пароли

### ❌ Неправильно:
- Загружать keystore в Git/GitHub
- Хранить пароли в build.gradle.kts
- Делиться keystore с кем-либо
- Терять keystore (без него нельзя обновить приложение в Google Play!)

## Восстановление keystore

Если вы потеряли keystore, восстановить его **НЕВОЗМОЖНО**! 

⚠️ Сделайте резервную копию:
1. Скопируйте `app/diceautobet-release.keystore` в безопасное место
2. Сохраните пароли из `keystore.properties`

Без keystore вы не сможете:
- Обновить приложение в Google Play
- Выпустить новую версию с тем же именем пакета

## Проверка keystore

Проверить информацию о keystore:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
& "$env:JAVA_HOME\bin\keytool.exe" -list -v -keystore app/diceautobet-release.keystore -alias diceautobet
```

## Дополнительная информация

- Keystore действителен 10,000 дней (~27 лет)
- Используется RSA 2048-bit ключ
- Алгоритм подписи: SHA384withRSA
- Alias ключа: `diceautobet`

---

**Готово!** Теперь ваше приложение будет автоматически подписываться при сборке Release APK.
