// script.js

// Имитируем базу данных имен
const validUsernames = ['роман', 'рома', 'стас', 'сус', 'станисла', 'станислав', 'станислау', 'саня', 'александр', 'саша', 'егор', 'уегорп', 'матвей', 'матрасик', 'матрас', 'стасик',  'сас',  'черт',  'чёрт',];

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
    'роман': 'Роман',
    'рома': 'Роман',
    'александр': 'александр',
    'саня': 'александр',
    'саша': 'александр',
    'егор': 'егор',
    'уегорп': 'егор',
    'матвей': 'Матвей'
};

// Функция для проверки имени и отображения приветственного сообщения
function handleFormSubmit(event) {
    event.preventDefault(); // Отменяет стандартное действие формы

    // Получаем значение из поля ввода
    const usernameInput = document.getElementById('username');
    const username = usernameInput.value.trim().toLowerCase();

    // Получаем элемент для отображения сообщения
    const greeting = document.getElementById('greeting');

    // Проверяем, есть ли имя в "базе данных"
    
    if (validUsernames.includes(username)) {
        if (usernameMapping[username]) {
            printResultText(usernameMapping[username]);
        }
    } else {
        greeting.textContent = 'Имя не найдено в базе данных.';
    }
    
}

function capitalizeFirstLetter(string) {
    return string.charAt(0).toUpperCase() + string.slice(1);
}

function printResultText(name) {
    ResultName = capitalizeFirstLetter(name)
    greeting.textContent = `Привет, ${ResultName}! 
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

// Назначаем обработчик события для кнопки
document.getElementById('openLinkButton').addEventListener('click', openLink);
document.getElementById('openLinkButton2').addEventListener('click', openLink2);