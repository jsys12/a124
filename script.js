// Имитируем базу данных имен
const validUsernames = ['даня', 'данил', 'ваня', 'даниил', 'иван', 'роман', 'рома', 'стас', 'сус', 'станисла', 'станислав', 'станислау', 'саня', 'александр', 'саша', 'егор', 'уегорп', 'матвей', 'матрасик', 'матрас', 'стасик', 'сас', 'черт', 'чёрт'];

const usernameMapping = {
    'сус': 'Станислав',
    'стас': 'Станислав',
    'стасик': 'Станислав',
    'сас': 'Станислав',
    'станисла': 'Станислав',
    'станислав': 'Станислав',
    'станислау': 'Станислав',
    'матрасик': 'Станислав',
    'матрас': 'Станислав',
    'черт': 'Станислав',
    'чёрт': 'Станислав',
    'чорт': 'Станислав',
    'чрт': 'Станислав',
    'чот': 'Станислав',
    'роман': 'Роман',
    'рома': 'Роман',
    'александр': 'Александр',
    'саня': 'Александр',
    'саша': 'Александр',
    'егор': 'Егор',
    'уегорп': 'Егор',
    'матвей': 'Матвей',
    'даниил': 'Даниил(пиво)',
    'иван': 'Иван',
    'ваня': 'Ваня',
    'данил': 'Данил',
    'даня': 'Даня'
};

// Функция для проверки имени и отображения приветственного сообщения
function handleFormSubmit(event) {
    event.preventDefault(); // Отменяет стандартное действие формы

    console.log("Форма отправлена"); // Отладочное сообщение

    // Получаем значение из поля ввода
    const usernameInput = document.getElementById('username');
    const username = usernameInput.value.trim().toLowerCase();

    console.log("Введенное имя: " + username); // Отладочное сообщение

    // Получаем элемент для отображения сообщения и GIF
    const greeting = document.getElementById('greeting');
    const gifContainer = document.getElementById('gifContainer');

    // Проверяем, есть ли имя в "базе данных"
    if (validUsernames.includes(username)) {
        const mappedName = usernameMapping[username];
        if (mappedName) {
            printResultText(mappedName);
            gifContainer.style.display = 'block'; // Показываем GIF
        } else {
            console.error("Имя найдено в базе данных, но не найдено в usernameMapping.");
        }
    } else {
        greeting.textContent = 'Имя не найдено в базе данных.';
        gifContainer.style.display = 'none'; // Скрываем GIF
    }
}


function capitalizeFirstLetter(string) {
    return string.charAt(0).toUpperCase() + string.slice(1);
}

function printResultText(name) {
    const resultName = capitalizeFirstLetter(name);
    const greeting = document.getElementById('greeting');
    greeting.textContent = `Привет, ${resultName}! 
Ты приглашён на мой день рождения, который, скорее всего, пройдёт 22 августа. Будем рубиться в пейнтбол! Если что-то изменится, я сообщу. Надеюсь, ты сможешь присоединиться! Дай знать, если будешь.
С уважением, Всеволод.`;
}

// Назначаем обработчик события для формы
document.getElementById('nameForm').addEventListener('submit', handleFormSubmit);

// Функция для открытия ссылки
function openLink() {
    window.open('https://t.me/username4332', '_blank'); // Открывает ссылку в новой вкладке
}

function openLink2() {
    window.open('https://t.me/+jAJPwVO1TwQ3ZmYy', '_blank'); // Открывает ссылку в новой вкладке
}

function fackeButton() {
    alert('Эт шутка');
}

// Назначаем обработчик события для кнопок
document.getElementById('openLinkButton').addEventListener('click', openLink);
document.getElementById('openLinkButton2').addEventListener('click', openLink2);
document.getElementById('fackeButton').addEventListener('click', fackeButton);
