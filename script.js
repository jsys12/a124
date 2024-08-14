// script.js

// Имитируем базу данных имен
const validUsernames = ['Роман', 'Рома', 'Стас', 'Сус', 'Станисла', 'Станислав', 'Станислау', 'Саня', 'Александр', 'Саша', 'Егор', 'Уегорп', 'Матвей', 'Матрасик', 'Матрас', 'Стасик',];

// Функция для проверки имени и отображения приветственного сообщения
function handleFormSubmit(event) {
    event.preventDefault(); // Отменяет стандартное действие формы

    // Получаем значение из поля ввода
    const usernameInput = document.getElementById('username');
    const username = usernameInput.value.trim();

    // Получаем элемент для отображения сообщения
    const greeting = document.getElementById('greeting');

    // Проверяем, есть ли имя в "базе данных"
    if (validUsernames.includes(username)) {
        greeting.textContent = `Привет, ${username}! 
Ты приглашён на мой день рождения, который, скорее всего, пройдёт 22 августа. Будем рубиться в пейнтбол! Если что-то изменится, я сообщу. Надеюсь, ты сможешь присоединиться! Дай знать, если будешь.
С уважением, Всеволод.`;
    } else {
        greeting.textContent = 'Имя не найдено в базе данных.';
    }
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